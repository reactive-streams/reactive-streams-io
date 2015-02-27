package org.reactivestreamsio.tcp;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * An abstraction for a TCP connection.
 *
 * <h2>Reading data</h2>
 * This connection is a {@link Publisher} and hence one can call {@link Publisher#subscribe(Subscriber)} to start
 * reading data from the connection.
 *
 * <h3>Multiple read subscriptions</h3>
 * This connection does <em>not</em> allow multiple subscription to the content and any subscriptions post the first
 * subscriber, will receive an error.
 *
 * <h2>Ignoring data</h2>
 *
 * Since, this connection does not read any data from the underneath socket, unless someone subscribes, a convenience
 * method for ignoring all data on the socket is provided as {@link #ignoreContent()}
 *
 * <h2>Writing data</h2>
 *
 * This connection provides two ways of writing data:
 * <ul>
 <li><i>Single Message:</i> One can write one item at a time, using {@link #write(Object)}</li>
 <li><i>Message stream:</i> One can provide a {@link Publisher} of data, using {@link #write(Publisher)}. This
 connection will subscribe to such a {@link Publisher} and write all items emitted by that publisher on the connection.
 </li>
 </ul>
 *
 * Writing any items using these methods, do <em>not</em> actually write the data on the underneath socket. An explicit
 * {@link #flush()} call is required to write the data on the underneath socket.
 * One can also use the shorthand {@link #writeAndFlush(Object)} or {@link #writeAndFlush(Publisher)} to write and flush
 * the item(s)
 *
 * <h2>Flush</h2>
 *
 * In order to optimize the number of system calls to write data on the underneath socket, this connection breaks the
 * writing process into two parts, viz., write and flush. A flush call writes all previous writes on this connection on
 * the underneath socket.
 *
 * <h2>Write errors</h2>
 *
 * Every write call returns a {@link Publisher} that represents the acknowledgment of the write action. If the write
 * failed, then the subscriber's {@link Subscriber#onError(Throwable)} method will be called and if it succeeds, then
 * subscriber's {@link Subscriber#onComplete()} method will be called
 *
 * <h2>Flush errors</h2>
 *
 * Since, a {@link #flush()} is a batch operation encompassing all previous unflushed writes, it will represent the
 * result of all those writes. It will fail if any one of the writes fail and it will not be possible to correlate which
 * write caused the flush to fail. In order to get granular result per write, one has to subscribe to the
 * {@link Publisher} of that particular write.
 *
 * <h2>Close</h2>
 *
 * Since, this connection only allows a single subscription, cancelling that subscription
 * (via {@link Subscription#cancel()}) closes the connection.
 * For cases which ignores content (via {@link #ignoreContent()}), an explicit {@link #close()} is required to be
 * invoked for closing this connection.
 *
 * @param <R> Type of objects read from this connection.
 * @param <W> Type of objects written to this connection.
 */
public interface TcpConnection<R, W> extends Publisher<R> {

    /**
     * Subscribes to the content stream of this connection.
     *
     * <h2>Multiple subscriptions</h2>
     * This connection only allows a single subscription to the content stream. Any subsequent subscription will result
     * in invocation of {@link Subscriber#onError(Throwable)} on that subscriber.
     *
     * @param subscriber Subscriber for the content.
     */
    @Override
    void subscribe(Subscriber<? super R> subscriber);

    /**
     * Ignores all content of this connection. This is equivalent to calling {@link #subscribe(Subscriber)} with a
     * subscriber that discards all received items.
     */
    void ignoreContent();

    /**
     * On subscription of the returned {@link Publisher}, writes the passed {@code msg} on this connection.
     * Every subscription will write the same {@code msg} on the connection.
     *
     * <b>This does not flush the write.</b> Call {@link #flush()} to flush this write.
     * @param msg Message to write.
     *
     * @return A {@link Publisher} representing the result of this write. One has to subscribe to this {@link Publisher}
     * to trigger the write. Each subscription will trigger a write of the same message. The subscriber to this
     * {@link Publisher} will not complete unless {@link #flush()} is called on this connection.
     */
    Publisher<Void> write(W msg);

    /**
     * On subscription of the returned {@link Publisher}, subscribes to the passed {@code msgPublisher} and writes each
     * emitted item on this connection.
     *
     * <b>This does not flush the write.</b> Call {@link #flush()} to flush this write.
     *
     * @param msgPublisher A {@link Publisher} of messages which are to be written to this connection.
     *
     * @return A {@link Publisher} representing the result of this write. One has to subscribe to this {@link Publisher}
     * to trigger the write. Each subscription will trigger a subscription to the {@code msgPublisher}. The subscriber
     * to this {@link Publisher} will not complete unless {@link #flush()} is called on this connection.
     */
    Publisher<Void> write(Publisher<W> msgPublisher);

    /**
     * On subscription of the returned {@link Publisher}, flushes all previously unflushed writes on the underneath
     * socket.
     *
     * @return A {@link Publisher} representing the result of this flush. One has to subscribe to this {@link Publisher}
     * to trigger the flush.
     */
    Publisher<Void> flush();

    /**
     * A shorthand for {@link #write(Object)} and {@link #flush()}. Every subscription to the returned {@link Publisher}
     * will first write the passed {@code message} and then flush.
     *
     * <h2>Pending write failures and this result</h2>
     *
     * The returned {@link Publisher} only provides the result of this particular write and not any other writes which
     * were not flushed before this write.
     *
     * @param msg Message to write and flush.
     *
     * @return A {@link Publisher} representing the result of this write. One has to subscribe to this {@link Publisher}
     * to trigger the write and flush. Each subscription will trigger a write of the same message.
     */
    Publisher<Void> writeAndFlush(W msg);

    /**
     * A shorthand for {@link #write(Publisher)} and {@link #flush()}. Every subscription to the returned
     * {@link Publisher} will first subscribe to the passed {@code msgPublisher}, write all emitted items by that
     * publisher and then flush.
     *
     * <h2>Pending write failures and this result</h2>
     *
     * The returned {@link Publisher} only provides the result of this particular write and not any other writes which
     * were not flushed before this write.
     *
     * <h2>Flush</h2>
     *
     * Flush will only be called after receiving an {@link Subscriber#onComplete()} from {@code msgPublisher}
     *
     * @param msgPublisher A {@link Publisher} of messages which are to be written and flushed to this connection.
     *
     * @return A {@link Publisher} representing the result of this write. One has to subscribe to this {@link Publisher}
     * to trigger the write and flush. Each subscription will trigger a write of the same message.
     */
    Publisher<Void> writeAndFlush(Publisher<W> msgPublisher);

    /**
     * Closes this connection on subscription of the returned {@link Publisher}.
     *
     * <h2>Multiple subscriptions</h2>
     *
     * Multiple subscriptions are allowed to the returned {@link Publisher} but only the first subscription closes the
     * underneath socket. All other subscriptions receive the same result, which is the result of the close of the
     * underneath socket.
     *
     * @return {@link Publisher}, first subscription to which closes the connection.
     */
    Publisher<Void> close();
}
