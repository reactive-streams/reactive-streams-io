Status: this is a rough suggestion, just to get some ideas out there. 

Message framing will use protobuf. It's true that we don't need the full complexity of protobuf, but future protocol extensions might benefit. Also, an implementation might use a protobuf description of the published messages to decode both the framing and the messages in one go.

Let a message frame = message type + size + contents. And let type Id = varint. The basic RS signalling would be:

    --> subscribe(publisher: Id, subscriber: Id, initialDemand: Long = 0)
    --> request(subscriber: Id, demand: Long)
    <-- subscribed(subscriber: Id)
    <-- onNext(subscriber: Id, element: bytes)
    <-- onComplete(subscriber: Id)
    <-- onError(subscriber: Id, error: String)

There is no separate Subscription object or id; the subscriber Id identifies the recipient in all messages going <-- this way. Each side must manage the mapping of Ids to publishers and subscribers. (Publisher Ids and subscriber Ids have separate namespaces.)

The Ids are only large enough to identify components, not to describe them. So the discovery of publishers and their Ids must either happen out of band, or require more messages, which could form an optional protocol on top of this one (i.e. using other message type codes). The same is true for clients that ask the server to create parameterized publishers (e.g. to read a particular named file).

The overhead per message is typically 1 byte (message code) +  1-2 bytes (recipient id) + 2-3 bytes (message length). When the message type is very small (e.g. an int), this is too much. There are three possible ways of handling this:

  1. Send many messages at once, up to the current demand: `--> onNext(subscriber: Id, element: bytes, ...)`. The main problem with this is that the [WIP]
