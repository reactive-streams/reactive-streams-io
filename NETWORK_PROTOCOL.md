Status: this is a rough draft, intended to get some ideas out there. 

Missing: how to run on top of HTTP/2. Possibly the structure or semantics of publisher name strings.
Might be removed: extension support.
Need to choose serialization method (protobuf, msgpack, other?)

## Transport assumptions

A supporting transport must be similar to a streaming socket:

1. Bidirectional and full-duplex
2. An octet stream, i.e. all octet values may be sent unencoded
3. Ordered delivery: an implementation may map protocol messages to some features of the underlying transport (e.g. [Ømq](http://zeromq.org/) messages), but the messages must arrive in the same order as they were sent

Definitely supported transports include TCP, TLS (over TCP), WebSockets, and most socket-like objects (e.g. pipes). HTTP/2 will be supported, but may require a dedicated specification for implementing this protocol.

## Message framing

An existing serialization format should be used. Current candidates are [Protocol Buffers](https://github.com/google/protobuf/) (which is slightly less space efficient), [MessagePack](http://msgpack.org/) (whose Scala implementation may not be as good as the others), and possibly [Thrift](https://thrift.apache.org/) (with which I'm less familiar). 

The serialization format should be fast and space-efficient. It does not need to be self-describing, since the message types and their structures are fully specified in the protocol. It needs to have the types boolean, string / byte array (length-prefixed), and varint (an integer encoded using 1 or more bytes depending on its value).

The full complexity of these formats may not be needed today, but future protocol extensions might benefit. Also, an implementation might encode the published elements using the same format and decode both the framing and the messages using the same parser.

Each message (frame) begins with a message type, which is a varint, followed by its contents. Messages are self-delimiting, because their structure is known from their type, and all fields are either of fixed size, self-delimited varints, or length-prefixed strings or byte arrays.

## Protocol negotiation

The protocol is versioned and supports future extensions. The client (i.e. the side that opened the connection) and the server do a loose handshake:

    --> clientHello(version: varint, extensions: Array[Id])
    <-- serverHello(version: varint, extensions: Array[Id])
    
This is a 'loose' handshake because the server doesn't have to wait for the `clientHello` before sending its `serverHello`. 
    
The protocol version is currently version 0. If either side receives a hello message with a version it doesn't support, it MUST send a `goodbye` message (defined below) and close the connection. When future versions of the protocol introduce incompatible changes and increment the version number, transports SHOULD indicate the incompatibility when suitable, e.g. by changing the HTTP Content-Type or TCP port number).
    
Extension to the protocol specify optional or future behaviors. 
 1. If an extension defines a new message type not described in this specification, that message MUST NOT be sent before receiving a hello from the other side confirming that it supports that extension. 
 2. If an extension changes the semantics of message types defined in this specification or by another extension, the modified behavior MUST be negotiated by at least one of the parties sending, and the other acknowledging, a message (defined by the extension being discussed) that declares the new behavior as active. A party supporting such an extension SHOULD NOT send messages whose semantics are modified by it before this negotiation is completed (i.e. the acknowledgement message is received).
    
The client can optimistically send more messages after the `clientHello` without waiting for the `serverHello`. If it eventually receieves a `serverHello` with a different protocol version, it must consider that its messages were discarded. Future protocol versions will not be backward-compatible with version 0, in the sense that if a server multiple versions (e.g. both version 0 and some future version 1), it must wait for the `clientHello` and then send a `serverHello` with a version number matching the client's.

## The Reactive Streams core protocol

The basic RS signalling is:

    --> subscribe(publisher: String, subscriber: Id, initialDemand: Long = 0)
    --> request(subscriber: Id, demand: Long)
    --> cancel(subscriber: Id)
    <-- onSubscribe(subscriber: Id, elementSize: varint = 0) // For elementSize != 0, see the next section
    <-- onNext(subscriber: Id, element: bytes) 
    <-- onComplete(subscriber: Id)
    <-- onError(subscriber: Id, error: String)
    
The protocol is fully bidirectional; either party can act in the `-->` direction. The semantics for ordering and asynchronous delivery are the same as in the Reactive Streams specification.

Unlike in RS, there is no separate Subscription object; the subscriber Id identifies the recipient in all messages going <-- this way. This id is generated by the subscriber and sent in the `subscribe` message.

The publisher String needs to be parsed by the recipient; it is not described by this specification. [Could be added?]

The field `onSubscribe.elementSize`, if nonzero, indicates the fixed size of the elements that will be published in this stream. In fixed-size mode, the `onNext.element` field is not length-prefixed. This saves space when the messages are very small, such as individual ints.

After a subscription is closed, its Id can be reused, to prevent Ids from growing without limit. The subscriber MAY reuse an Id in a `subscribe` message after it has sent `cancel` or received `onComplete` or `onError` for that Id. If it does so, it MUST guarantee that the publisher will not receive messages meant for the previous subscription with that Id after it receives the second `subscribe` message.

## Packed messaging

In typical use, the most common messages by far are `onNext`. The overhead per message is typically 1 byte (message code) +  1-2 bytes (subscriber id) + 1-3 bytes (payload length) = 3-6 bytes total. When the message type is very small (e.g. an int), the overhead can be 100% or more.

To reduce the overhead, the publisher can optionally declare that all stream elements will have a fixed size by setting the `onSubscribe.elementSize` field to a value greater than zero:

    <-- onSubscribe(subscriber: Id, elementSize: varint)

The publisher can then send not just `onNext` messages but also `onNextPacked` messages:

    <-- onNextPacked(subscriber: Id, count: varint, messages: count * elementSize bytes)
    
Packing does not help if new data becomes available very frequently and must not be delayed before being sent. A typical example is a ticker source. It also can't be done if the client doesn't provide enough demand.

## Split messaging

A very large `onNext` message might significantly delay messages from other streams. Therefore, large stream elements can be optionally split across multiple messages. Publishers MAY split elements; subscribers MUST support this.

When an element is split, the publisher will send one or more `onNextPart` messages, followed by a single `onNextLastPart`:

    <-- onNextPart(subscriber: Id, element: Id, data: bytes)
    <-- onNextLastPart(subscriber: Id, element: Id, data: bytes)

`element` is an Id assigned by the Publisher; messages with the same `element` value, in the same stream, will be joined by the Subscriber. The order of the parts is that in which they were sent and received (the transport is required to provide ordered delivery).

The subscriber's driver will typically join the parts transparently and deliver a single message to the application.

## Closing the connection

When the underlying transport is closed, both sides should release all related resources. This protocol version does not specify reusing previously negotiated state after reconnecting.

The orderly way of closing a connection is to send a `goodbye` message, wait for acknowledgement and then close the underlying connection:

    --> goodbye(reason: String)
    <-- goodbye(reason: String)
    
Sending `goodbye` implicitly closes all open streams, equivalently to receiving `cancel` or `onError` messages.
