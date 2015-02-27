package org.reactivestreamsio.tcp;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.net.SocketAddress;

/**
 * A TCP client.
 *
 * @param <IN> Type of object written on a TCP connection created by this client.
 * @param <OUT> Type of object read from a TCP connection created by this client.
 */
public interface TcpClient<IN, OUT> {

    /**
     * A request for connections, upon calling {@link Publisher#subscribe(Subscriber)} on the returned {@link Publisher}.
     * Each subscription can request as many connections as required by using {@link Subscription#request(long)}
     *
     * @param remoteAddress The remote address to connect.
     *
     * @return A {@link Publisher} of {@link TcpConnection}
     */
    Publisher<TcpConnection<OUT, IN>> connect(SocketAddress remoteAddress);
}
