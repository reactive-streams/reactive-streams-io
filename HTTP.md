## Why HTTP?

In terms of protocol design, HTTP is clearly less convenient than plain TCP. The reasons one might want to use HTTP anyway are:

1. To support Javascript in browsers, i.e. web applications. (This does not seem very important, since web sockets are usually a better solution.)
2. To easily implement RS.io in new languages / frameworks, which have good HTTP support, whereas asynchronous socket IO and protobuf require more work.
3. To easily host RS.io implementations on existing web servers under a new URL, possibly as servlets or equivalents.
4. To avoid having to open new ports and convince the network admins to open them in the firewall.
5. To reuse HTTP's SSL support and the platform's configuration and management tools for trusted certificates, or HTTP-based authentication mechanisms.
6. To reuse HTTP routing, placing different subscriptions on different hosts which have the requisite data, or for load-balancing.

Implementors should keep in mind that RS.io over HTTP will always be at least somewhat slower than over TCP, and so should use TCP or Websockets when HTTP doesn't bring any particular benefits.

## Mapping RS.io to HTTP, with the client as the subscriber

In HTTP/1.1, to allow both sides to send events, we need to use two connections: one for ordinary client requests, and one for SSE (server-sent events) or long polling to allow the server to produce messages asynchronously. In HTTP/2, server push is used instead. 

All requests use the PUT method. GET is clearly unsuitable, because all requests change server state and are not idempotent. POST would be slightly better semantically, but it is [cacheable](https://tools.ietf.org/html/rfc7231#section-4.2.3) by default, whereas PUT is not. To use POST we would have to add a `Cache-Control: no-cache` header to every response, which would greatly increase the overhead of element messages. This is somewhat against the spirit of RESTful HTTP, but since we don't support any other methods on the same URLs, it is acceptable.

The publisher and subscription URLs MUST NOT have a query string that cannot be parsed according to the x=y&a=b representation (which is not specified in any HTTP standard). If they do have a query string, it MUST NOT include arguments with the same names as those specified here (`request`).

This table summarizes the HTTP messages used:

RS call equivalent      | HTTP request                    | HTTP responses
------------------------|---------------------------------|--------------
subscribe, onSubscribe  | PUT /publisher?request=         | 201 Created; Location: subscription URL
onNext, onComplete      | PUT /subscription?request=      | 200 OK; entity = single element
                        |                                 | 200 OK; Content-Encoding: X-Rsio-LengthPrefixedElements; entity contains multiple elements
                        |                                 | 200 OK; Content-Encoding: X-Rsio-FixedSizeElements; entity contains multiple elements
                        |                                 | 204 No Content; no elements currently available
                        |                                 | 410 Gone; stream has ended (onComplete)
request                 | PUT /subscription/requestMore=  | 200 OK
                        | (or as part of subscribe or onNext) | 
cancel                  | PUT /subscription/cancel        | 200 OK
onError                 |                                 | Any 4xx or 5xx response code with an `X-Rsio-Error: true` header

For the reasoning behind the choice of the PUT method, see the note in the interoperability section.

### 1. Subscribing

To establish a logical RS.io stream, the (HTTP) client sends a subscribe request:

    PUT /publisherUrl?request=[int=0] HTTP/1.1

The url must identify to the server that this is a subscribe request and which publisher is desired. This specification doesn't define the URL's contents.

The `request` argument specifies initial demand without requiring a separate roundtrip. This translates to a `request` event on the server if it responds by accepting the subsription. If the `request` argument is absent, it is assumed to be 0. 

The server replies with a URL that will uniquely identify this subscription for its lifetime:

    HTTP/1.1 201 Created
    Location: [subscription url]
    
The subcription URL is a *globally* valid identifier and locator for this subscription. It can have a different host from the publisher url. Servers SHOULD, when possible, produce unique urls that will not be reused after the subscription has been canceled.

### 2. Getting stream elements

Once the client receives the subscription url, it uses polling or SSE to fetch server-sent messages. Alternatively, in HTTP/2, these messages can be pushed by the server. [This requires more details]

    PUT /subscriptionUrl?request=[int=0]
  
`request` is an optional parameter which, if absent, is treated as 0. If non-zero, it generates a `request` on the server.

Note that the method here is POST, not GET. This is because requests are not idempotent: the server will return a different element on every call, and the server state changes as a result.

The server can reply with a stream element, which translates to the client receiving an `onNext` signal:

    HTTP/1.1 200 OK
  
The response entity contains the element data. 

The response SHOULD NOT include a `Content-Type` indicating the element's media type, because this is not possible when batching stream elements (see below).

#### 2.1 Batching stream elements

The server can also reply with several stream elements (up to the current demand) for efficiency:

    HTTP/1.1 200 OK
    Content-Encoding: X-Rsio-LengthPrefixedElements
    
    [length][element][length][element]....
    
    HTTP/1.1 200 OK
    Content-Encoding: X-Rsio-FixedSizeElements
    
    [N][element of size N bytes][element of size N bytes]....

[length] and [N] are encoded as 4-byte unsigned integers. 

The first variant encodes each element as a length prefix followed by that many bytes of data. The second encodes each element as having a fixed number of bytes, and is suitable for transporting many small elements.

When sending multiple elements, the server MUST NOT use the Content-Type or Content-Encoding headers to indicate the type or encoding of each individual element (since that would be against the HTTP spec). Stream publishers SHOULD either document the (single) content type of each element in their stream, or make the element payloads self-describing.

If a batched message is interrupted by a transport-level error (e.g. a network problem), there is no way for the server to know which batched elements, if any, were received by the client. Therefore, in case of an error, the server MUST NOT update its remaining demand count for the subscription, and on the next client request it MUST resend all of the elements in the batch. The server MAY batch them differently or not at all when resending. 

The client MUST either discard any elements it receives twice due to the server resending them, or not process them until it receives a complete batched response safely. The first option can be implemented by counting elements starting from the first one in the batch whose delivery failed.

#### 2.2 Deferring stream elements

When the server has no elements ready to publish to the stream, the request stalls. When using SSE / long polling, such a request may eventually time out, depending on network middleware and HTTP implementations used. 

Parties SHOULD NOT rely on sudden disconnections to indicate that no elements are available, because it is possible in a race condition that a message was sent by the server but the client disconnected just before receiving it. In this case, after reconnecting, the server will not send the message again, since it cannot know that it was not received.

Instead, a server with no stream elements to deliver SHOULD send a response before the timeout occurs:

    HTTP/1.1 204 No Content

If the server knows that the next element will not be available for some time, it SHOULD include a [Retry-After](https://tools.ietf.org/html/rfc7231#section-7.1.3) response header.

### 2.3 Indicating demand without fetching stream elements

When using HTTP/2 server push instead of HTTP/1.1 client polling, the client needs to send separate messages to indicate its demand to the server. This can also be used with HTTP/1.1, but there seems to be no reason to do so.

    PUT /subscriptionUrl/requestMore=[int>0]
  
The server MUST reply with:

    HTTP/1.1 200 No Content

### 3. Completing the stream

In response to a request for the next element, the server can return:

    HTTP/1.1 410 Gone
  
This translates to an `onComplete` event for the subscriber.

### 4. Canceling the stream

To generate a `cancel` event on the server, the client should send:

    PUT /subscriptionUrl/cancel
  
The server should reply with:

    HTTP/1.1 200 OK

### 5. Handling errors

When the server wishes to produce an `onError` event and cancel the stream, it can send in a reply to any request:

    HTTP/1.1 500 Internal Server Error
    X-Rsio-Error: true

The presence of the `X-Rsio-Error` header indicates to the client that this is an `onError` event and the stream subscription has definitely been canceled. It can be used with any 4xx or 5xx error code. The server SHOULD use the most appropriate response code with this header.

The server MUST send this header with some error response if it wishes to indicate to the client that the stream is canceled. It MUST NOT rely on error messages that do not contain this header.

In any error response that includes this header, the server SHOULD include an error message in the response entity. If the response has an entity, it MUST have some text/* content-type, and SHOULD have a text/plain content-type. 

When the client receives any 4xx or 5xx error that does not contain the `X-Rsio-Error` header, it MUST either retry the request (possibly after a delay), or generate an `onError` event. If it decides to generate `onError`, it MUST send an extra `cancel` message to the server, to make sure it releases the resources for this stream.

In the absence of an `X-Rsio-Error` header, the client SHOULD treat these HTTP response codes as indicating a final error: 

- 4xx errors that the client cannot or will not remedy by retrying with a suitably modified request.
- 501 Not Implemented.
- 502 Bad Gateway.
- 505 HTTP Version Not Supported.

#### 5.1 Requests to unregistered subscriptions

If the server receives a request to a subscription URL it does not recognize, possibly because the subscription has been cancelled, it SHOULD respond with:

    HTTP/1.1 404 Not Found
    X-Rsio-Error: true
    
The server SHOULD include the `X-Rsio-Error` header in case a client is improperly written and thinks the 404 Not Found response is retriable.

## Interoperability with standard HTTP mechanisms

- Any HTTP-based authentication or redirection mechanisms SHOULD take place before or in response to the initial subscription request. If, after successfully subscribing, the client receives an authentication challenge or a redirect 3xx message in response to any request made to the subscription URL, it MAY support them transparently. Servers SHOUD NOT issue a redirect message on requests to the subcription URL.

- Any supported `Transfer-Encoding` or `Content-Encoding` MAY be used on any message, transparently to RS.io, subject to the ordinary HTTP negotiation of these features. For `Content-Encoding` this applies to the overall message (i.e. some HTTP server specify `Content-Encoding: gzip` instead of `Transfer-Encoding: gzip`), and not to the individual stream entities.

- Other standard headers specified by HTTP are omitted from the examples in this document for brevity, but are assumed to be allowed, and present when mandated by HTTP. This includes e.g. `Content-Length` and `Date`. 

- The parties MUST NOT use content-type negotiation for RS.io messages, because the `Content-Type` header cannot apply to the individual stream entities in a batched message.

- The server SHOULD NOT provide an Etag or Last-Modified date with responses, since they do not describe entities which the client can access again without creating a new stream, which would likely have a different subscription URL.

- The client MUST NOT make any RS.io conditional requests using If-* headers. The server MUST reply to such requests with 412 Precondition Failed.

- The client MUST NOT make any RS.io range request using the `Range` header. The server MUST ignore the `Range` header on requests and respond as if it were not present (the HTTP specification allows this).

- The parties MUST NOT use HTTP trailers to attempt to change the RS.io semantics or entity data. This would be counterproductive, since most HTTP implementations do not support trailers.

## TODOs

It may be useful to specify some details or alternate behavior on HTTP/2 that takes advantage of its features.
