package com.ning.metrics.collector.endpoint.servers;

import com.ning.metrics.collector.util.NamedThreadFactory;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TNonblockingServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TNonblockingServerTransport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * An extension of the TNonblockingServer to a Half-Sync/Half-Async server.
 * Like TNonblockingServer, it relies on the use of TFramedTransport.
 */
public class CollectorTHsHaServer extends TNonblockingServer
{

    // This wraps all the functionality of queueing and thread pool management
    // for the passing of Invocations from the Selector to workers.
    private ExecutorService invoker;

    protected final int MIN_WORKER_THREADS;
    protected final int MAX_WORKER_THREADS;
    protected final int STOP_TIMEOUT_VAL;
    protected final TimeUnit STOP_TIMEOUT_UNIT;

    /**
     * Create server with given processor, and server transport. Default server
     * options, TBinaryProtocol for the protocol, and TFramedTransport.Factory on
     * both input and output transports. A TProcessorFactory will be created that
     * always returns the specified processor.
     */
    public CollectorTHsHaServer(TProcessor processor,
                                TNonblockingServerTransport serverTransport)
    {
        this(processor, serverTransport, new Options());
    }

    /**
     * Create server with given processor, server transport, and server options
     * using TBinaryProtocol for the protocol, and TFramedTransport.Factory on
     * both input and output transports. A TProcessorFactory will be created that
     * always returns the specified processor.
     */
    public CollectorTHsHaServer(TProcessor processor,
                                TNonblockingServerTransport serverTransport,
                                Options options)
    {
        this(new TProcessorFactory(processor), serverTransport, options);
    }

    /**
     * Create server with specified processor factory, server transport, and server
     * options. TBinaryProtocol is assumed. TFramedTransport.Factory is used on
     * both input and output transports.
     */
    public CollectorTHsHaServer(TProcessorFactory processorFactory,
                                TNonblockingServerTransport serverTransport,
                                Options options)
    {
        this(processorFactory, serverTransport, new TFramedTransport.Factory(),
            new TBinaryProtocol.Factory(), options);
    }

    /**
     * Create server with specified processor factory, server transport, in/out
     * transport factory, in/out protocol factory, and server options.
     */
    public CollectorTHsHaServer(TProcessorFactory processorFactory,
                                TNonblockingServerTransport serverTransport,
                                TFramedTransport.Factory transportFactory,
                                TProtocolFactory protocolFactory,
                                Options options)
    {
        this(processorFactory, serverTransport,
            transportFactory, transportFactory,
            protocolFactory, protocolFactory,
            options);
    }

    /**
     * Create server with every option fully specified.
     */
    public CollectorTHsHaServer(TProcessorFactory processorFactory,
                                TNonblockingServerTransport serverTransport,
                                TFramedTransport.Factory inputTransportFactory,
                                TFramedTransport.Factory outputTransportFactory,
                                TProtocolFactory inputProtocolFactory,
                                TProtocolFactory outputProtocolFactory,
                                Options options)
    {
        super(processorFactory, serverTransport,
            inputTransportFactory, outputTransportFactory,
            inputProtocolFactory, outputProtocolFactory,
            options);

        MIN_WORKER_THREADS = options.minWorkerThreads;
        MAX_WORKER_THREADS = options.maxWorkerThreads;
        STOP_TIMEOUT_VAL = options.stopTimeoutVal;
        STOP_TIMEOUT_UNIT = options.stopTimeoutUnit;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void serve()
    {
        if (!startInvokerPool()) {
            return;
        }

        // start listening, or exit
        if (!startListening()) {
            return;
        }

        // start the selector, or exit
        if (!startSelectorThread()) {
            return;
        }

        // this will block while we serve
        joinSelector();

        gracefullyShutdownInvokerPool();

        // do a little cleanup
        stopListening();

        // ungracefully shut down the invoker pool?
    }

    protected boolean startInvokerPool()
    {
        // start the invoker pool
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
        invoker = new ThreadPoolExecutor(MIN_WORKER_THREADS, MAX_WORKER_THREADS,
            STOP_TIMEOUT_VAL, STOP_TIMEOUT_UNIT, queue, new NamedThreadFactory("CollectorTHsHaServer worker"));

        return true;
    }

    protected void gracefullyShutdownInvokerPool()
    {
        // try to gracefully shut down the executor service
        invoker.shutdown();

        // Loop until awaitTermination finally does return without a interrupted
        // exception. If we don't do this, then we'll shut down prematurely. We want
        // to let the executorService clear it's task queue, closing client sockets
        // appropriately.
        long timeoutMS = 10000;
        long now = System.currentTimeMillis();
        while (timeoutMS >= 0) {
            try {
                invoker.awaitTermination(timeoutMS, TimeUnit.MILLISECONDS);
                break;
            }
            catch (InterruptedException ix) {
                long newnow = System.currentTimeMillis();
                timeoutMS -= (newnow - now);
                now = newnow;
            }
        }
    }

    /**
     * We override the standard invoke method here to queue the invocation for
     * invoker service instead of immediately invoking. The thread pool takes care of the rest.
     */
    @Override
    protected void requestInvoke(FrameBuffer frameBuffer)
    {
        invoker.execute(new Invocation(frameBuffer));
    }

    /**
     * An Invocation represents a method call that is prepared to execute, given
     * an idle worker thread. It contains the input and output protocols the
     * thread's processor should use to perform the usual Thrift invocation.
     */
    private class Invocation implements Runnable
    {

        private final FrameBuffer frameBuffer;

        public Invocation(final FrameBuffer frameBuffer)
        {
            this.frameBuffer = frameBuffer;
        }

        public void run()
        {
            frameBuffer.invoke();
        }
    }

    public static class Options extends TNonblockingServer.Options
    {
        public int minWorkerThreads = 5;
        public int maxWorkerThreads = Integer.MAX_VALUE;
        public int stopTimeoutVal = 60;
        public TimeUnit stopTimeoutUnit = TimeUnit.SECONDS;
    }
}