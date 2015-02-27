package org.reactivestreamsio.tcp;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import java.lang.IllegalStateException;

/**
 * A TCP server.
 *
 * @param <IN> Type of object read from a TCP connection accepted by this server.
 * @param <OUT> Type of object written to a TCP connection accepted by this server.
 */
public interface TcpServer<IN, OUT> {

    /**
     * Starts this server and sends all client connections accepted by this server to the passed
     * {@code connectionHandler}.
     *
     * @param connectionHandler Connection handler that receives all connections accepted by this server.
     *
     * @return This server.
     *
     * @throws IllegalStateException If this server is already started.
     */
    TcpServer<IN, OUT> start(ConnectionHandler<IN, OUT> connectionHandler);

    /**
     * Same as calling {@code start(TcpServer.ConnectionHandler).awaitShutdown()}.
     *
     * @param connectionHandler Connection handler that receives all connections accepted by this server.
     */
    void startAndAwait(ConnectionHandler<IN, OUT> connectionHandler);

    /**
     * Shutdown this server.
     *
     * @throws IllegalStateException If this server is already shutdown.
     */
    void shutdown();

    /**
     * Blocks the calling thread till this server is shutdown. Useful when this server controls the lifecycle of the
     * process creating this server.
     */
    void awaitShutdown();

    /**
     * Returns the port on which this server is listening.
     *
     * <h2>Ephemeral ports</h2>
     * If this server is created to run on an ephemeral port, then this method would return 0, unless, the server is
     * started via {@link #start(TcpServer.ConnectionHandler)}
     *
     * @return The port on which this server is listening for client connections.
     */
    int getServerPort();

    /**
     * A receiver for all accepted client {@link TcpConnection} by the associated server.
     *
     * @param <IN> Type of object read from a TCP connection accepted by this handler.
     * @param <OUT> Type of object written to a TCP connection accepted by this handler.
     */
    interface ConnectionHandler<IN, OUT> {

        /**
         * A callback for handling a newly accepted client {@link TcpConnection} by the associated server.
         *
         * @param newConnection Newly accepted client connection.
         *
         * @return {@link Publisher}, a subscription to which should start the processing of the {@code newConnection}.
         * On calling {@link Subscription#cancel()} on the subscription should cancel the processing of this connection.
         */
        Publisher<Void> handle(TcpConnection<IN, OUT> newConnection);
    }
}
