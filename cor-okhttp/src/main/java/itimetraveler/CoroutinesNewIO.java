package itimetraveler;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.FiberExecutorScheduler;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import okhttp3.RealCall;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class CoroutinesNewIO {

    private static final int fiberPoolSize = 1;
    private static FiberExecutorScheduler fiberExecutorScheduler;

    // The selector we'll be monitoring
    private Selector selector;

    // The buffer into which we'll read data when it's available
    private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

    // A list of PendingChange instances
    private List pendingChanges = new LinkedList();

    // Maps a SocketChannel to a list of ByteBuffer instances
    private Map pendingData = new HashMap();

    // Maps a SocketChannel to a main.java.nioexample.RspHandler
    private Map rspHandlers = Collections.synchronizedMap(new HashMap());

    // Maps a SocketChannel to a Fiber
    private Map fiberMap = new HashMap<SocketChannel, Fiber>();


    private static class SingletonHolder {
        static CoroutinesNewIO instance = new CoroutinesNewIO();
    }

    public static CoroutinesNewIO getInstance() {
        return SingletonHolder.instance;
    }

    private CoroutinesNewIO() {
        fiberExecutorScheduler = fiberExecutorScheduler();
        try {
            selector = SelectorProvider.provider().openSelector();
        } catch (IOException e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                init();
            }
        }).start();
    }

    public void execute(final RealCall.AsyncCall call) {
        new Fiber<Void>(fiberExecutorScheduler) {
            @Override
            protected Void run() throws SuspendExecution, InterruptedException {
                call.run();
                return null;
            }
        }.start();
    }

    @Suspendable
    public SocketChannel connect(InetSocketAddress address, int connectTimeout) throws SuspendExecution {

        try {
            // Create a non-blocking socket channel
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);

            // Kick off connection establishment
            printlog("[" + Thread.currentThread().getId() + "] address => " + address);
            socketChannel.connect(address);

            // Queue a channel registration since the caller is not the
            // selecting thread. As part of the registration we'll register
            // an interest in connection events. These are raised when a channel
            // is ready to complete connection establishment.
            synchronized(this.pendingChanges) {
                this.pendingChanges.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT));
            }

            // Finally, wake up our selecting thread so it can make the required changes
            this.selector.wakeup();

            printlog("[" + Thread.currentThread().getId() + "] fiber park => fiber:"+ Fiber.currentFiber().getId());
            if (Fiber.isCurrentFiber()) {
                fiberMap.put(socketChannel, Fiber.currentFiber());
                Fiber.park();
            }

            printlog("[" + Thread.currentThread().getId() + "] return socketChannel => " + socketChannel + "   isConnected: "+ socketChannel.isConnected());
            return socketChannel;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private void init() {
        while (true) {
            try {
                // Process any pending changes
                synchronized (this.pendingChanges) {
                    Iterator changes = this.pendingChanges.iterator();
                    while (changes.hasNext()) {
                        ChangeRequest change = (ChangeRequest) changes.next();
                        switch (change.type) {
                            case ChangeRequest.CHANGEOPS:
                                SelectionKey key = change.socket.keyFor(this.selector);
                                key.interestOps(change.ops);
                                break;
                            case ChangeRequest.REGISTER:
                                change.socket.register(this.selector, change.ops);
                                break;
                        }
                    }
                    this.pendingChanges.clear();
                }

                // Wait for an event one of the registered channels
                //printlog(selector.select() start.");
                this.selector.select();
                //printlog(selector.select() end.");

                // Iterate over the set of keys for which events are available
                Iterator selectedKeys = this.selector.selectedKeys().iterator();
                while (selectedKeys.hasNext()) {
                    SelectionKey key = (SelectionKey) selectedKeys.next();
                    selectedKeys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    // Check what event is available and deal with it
                    if (key.isConnectable()) {
                        this.finishConnection(key);
                    } else if (key.isReadable()) {
                        //this.read(key);
                    } else if (key.isWritable()) {
                        //this.write(key);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private void finishConnection(SelectionKey key) throws SuspendExecution {
        SocketChannel socketChannel = (SocketChannel) key.channel();

        // Finish the connection. If the connection operation failed
        // this will raise an IOException.
        try {
            socketChannel.finishConnect();
        } catch (IOException e) {
            // Cancel the channel's registration with our selector
            printlog("finishConnection() throws IOException:" + e.toString());
        } finally {
            key.cancel();
            Fiber fiber = (Fiber) fiberMap.get(socketChannel);
            if (fiber != null) {
                printlog("[" + Thread.currentThread().getId() + "] fiber Unpark => fiber：" + fiber.getId() + "， fiber.isAlive():"+ fiber.isAlive());
                fiber.unpark();
            }
        }

        // Register an interest in writing on this channel
        //key.interestOps(SelectionKey.OP_WRITE);
    }

    private FiberExecutorScheduler fiberExecutorScheduler() {
        String name = "CustomFiberScheduler";
        Executor exe = Executors.newFixedThreadPool(fiberPoolSize, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r);
            }
        });
        return new FiberExecutorScheduler(name, exe, null, true);
    }

    private static void printlog(String log) {
        System.out.println("[Tid=" + Thread.currentThread().getId() + "] " + log);
    }
}
