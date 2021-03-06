package com.goodworkalan.manifold;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A selector socket thread manager that reads and writes to a server sockets
 * and delegates data processing to a fixed array of worker threads.
 * 
 * @author Alan Gutierrez
 */
public class Manifold {
    // TODO Document.
    private final Thread thread;

    // TODO Document.
    private final CountDownLatch running;
    
    // TODO Document.
    private final ServerSocketChannel serverSocketChannel;
    
    // TODO Document.
    private final Queue<Plenum> plenums;
    
    // TODO Document.
    private final Selector selector;
    
    // TODO Document.
    private final Map<SocketChannel, Conversation> conversations;
    
    // TODO Document.
    private final ExecutorService executorService;
    
    // TODO Document.
    private final SessionFactory sessionFactory;
    
    // TODO Document.
    private final AtomicBoolean terminated;
    
    // TODO Document.
    private final int minimumLatency = 500;
    
    // TODO Document.
    private final int maximumLatency = 3000;
    
    // TODO Document.
    private final int maximumPlenums = 12;
    
    // TODO Document.
    public Manifold(SessionFactory sessionFactory, ExecutorService executorService) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        InetSocketAddress socketAddress = new InetSocketAddress("localhost", 8143);
        serverSocketChannel.socket().bind(socketAddress);
        Selector selector = SelectorProvider.provider().openSelector();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        this.selector = selector;
        this.plenums = new LinkedList<Plenum>();
        this.conversations = new ConcurrentHashMap<SocketChannel, Conversation>();
        this.executorService = executorService;
        this.sessionFactory = sessionFactory;
        this.terminated = new AtomicBoolean();
        this.running = new CountDownLatch(1);
        this.thread = new Thread() {
            @Override
            public void run() {
                try {
                    bind();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
    
    // TODO Document.
    void terminate(Conversation conversation) {
        synchronized (conversation) {
            conversation.operations.add(new Terminate(conversation));
        }
        executorService.execute(new Operation(conversation));
    }

    // TODO Document.
    private void accept(SelectionKey key) throws IOException {
        // For an accept to be pending the channel must be a server socket channel.
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

        // Accept the connection and make it non-blocking
        SocketChannel socketChannel = serverSocketChannel.accept();

        Session session = sessionFactory.accept(socketChannel.socket().getInetAddress());
        if (session == null) {
            socketChannel.close();
        } else {
            Plenum delegate = null;

            Plenum dillying = null;
            Plenum dallying = null;
            int min = -1;
            int plenumCount = plenums.size();
            int remaining = plenumCount;
            while (remaining != 0 && (min < minimumLatency || (dillying != null && dallying == null))) {
                int bestOf = Math.min(remaining, 5);
                while (bestOf-- != 0) {
                    Plenum plenum = plenums.poll();

                    int average = plenum.getAverage();

                    if (average > min) {
                        min = average;
                        delegate = plenum;
                    }

                    if (average > maximumLatency && plenumCount > 1) {
                        if (dillying == null) {
                            dillying = plenum;
                            plenums.add(plenum);
                        } else {
                            dallying = plenum;
                        }
                    } else {
                        plenums.add(plenum);
                    }
                }
                
                remaining--;
            }
            
            if (dillying != null && dallying != null) {
                dallying.handoff(dillying);
            }

            if (delegate == null || (min < minimumLatency && plenumCount < maximumPlenums)) {
                delegate = new Plenum(executorService);
                new Thread(delegate).start();
                plenums.add(delegate);
            }
            
            Conversation conversation = new Conversation(delegate, socketChannel, session);
            delegate.accept(conversation);
        }
    }

    // TODO Document.
    public void start() {
        thread.start();
    }
    
    // TODO Document.
    public void join() {
        try {
            thread.join();
        } catch (InterruptedException e) {
        }
    }
    
    // TODO Document.
    private void bind() throws IOException {
        running.countDown();
        bind(new NormalSelection(selector, terminated));
        serverSocketChannel.keyFor(selector).cancel();
        for (Conversation conversation : conversations.values()) {
            terminate(conversation);
        }
        selector.wakeup();
        bind(new ShutdownSelection(selector, conversations));
        bind(new PurgeSelection(selector));
        serverSocketChannel.close();
    }

    // TODO Document.
    public void waitForStartup() {
        try {
            running.await();
        } catch (InterruptedException e) {
        }
    }

    // TODO Document.
    private void bind(Selection selection) throws IOException {
        while (selection.select()) {
            Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
            while (selectedKeys.hasNext()) {
                SelectionKey key = selectedKeys.next();
                selectedKeys.remove();

                if (key.isValid() && key.isAcceptable()) {
                    accept(key);
                }
            }
        }
    }

    // TODO Document.
    public void shutdown() {
        terminated.set(true);
        selector.wakeup();
    }
}
