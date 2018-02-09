package cn.diyai.zookeeper;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;

import cn.diyai.jute.BinaryOutputArchive;
import cn.diyai.zookeeper.ZooKeeper.WatchRegistration;

import cn.diyai.jute.BinaryInputArchive;
import cn.diyai.jute.Record;
import cn.diyai.zookeeper.proto.ConnectRequest;
import cn.diyai.zookeeper.proto.ConnectResponse;
import cn.diyai.zookeeper.proto.ReplyHeader;
import cn.diyai.zookeeper.proto.RequestHeader;
import cn.diyai.zookeeper.server.ByteBufferInputStream;
import cn.diyai.zookeeper.server.ZooTrace;
import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback;

/**
 * Created by diyai on 2018/2/9.
 */
public class ClientCnxn {

    private static final Logger LOG = Logger.getLogger(ClientCnxn.class);
    private static boolean disableAutoWatchReset;
    final SendThread sendThread;
    private volatile int negotiatedSessionTimeout;
    private ZooKeeper zooKeeper;
    private int readTimeout;
    private  int sessionTimeout;
    private int connectTimeout;
    private long sessionId;
    private byte sessionPasswd[] = new byte[16];
    final String chrootPath;
    volatile boolean closing = false;
    volatile long lastZxid;

    private final ArrayList<InetSocketAddress> serverAddrs =
            new ArrayList<InetSocketAddress>();

    static class AuthData {
        AuthData(String scheme, byte data[]) {
            this.scheme = scheme;
            this.data = data;
        }

        String scheme;

        byte data[];
    }

    private final ArrayList<AuthData> authInfo = new ArrayList<AuthData>();

    /**
     * These are the packets that have been sent and are waiting for a response.
     */
    private final LinkedList<Packet> pendingQueue = new LinkedList<Packet>();

    /**
     * 等待发送请求队列
     * These are the packets that need to be sent.
     */
    private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();

    private int nextAddrToTry = 0;


    static {
        disableAutoWatchReset =
                Boolean.getBoolean("zookeeper.disableAutoWatchReset");
        if (LOG.isDebugEnabled()) {
            LOG.debug("zookeeper.disableAutoWatchReset is "
                    + disableAutoWatchReset);
        }
    }

    public long getSessionId() {
        return sessionId;
    }
    public byte[] getSessionPasswd() {
        return sessionPasswd;
    }


    public void start(){
        sendThread.start();
        eventThread.start();

    }

    /**
     * Returns the address to which the socket is connected.
     * @return ip address of the remote side of the connection or null if
     *         not connected
     */
    SocketAddress getRemoteSocketAddress() {
        // a lot could go wrong here, so rather than put in a bunch of code
        // to check for nulls all down the chain let's do it the simple
        // yet bulletproof way
        try {
            return ((SocketChannel)sendThread.sockKey.channel())
                    .socket().getRemoteSocketAddress();
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * Returns the local address to which the socket is bound.
     * @return ip address of the remote side of the connection or null if
     *         not connected
     */
    SocketAddress getLocalSocketAddress() {
        // a lot could go wrong here, so rather than put in a bunch of code
        // to check for nulls all down the chain let's do it the simple
        // yet bulletproof way
        try {
            return ((SocketChannel)sendThread.sockKey.channel())
                    .socket().getLocalSocketAddress();
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * This class allows us to pass the headers and the relevant records around.
     */
    static class Packet {
        RequestHeader header;

        ByteBuffer bb;

        /** Client's view of the path (may differ due to chroot) **/
        String clientPath;
        /** Servers's view of the path (may differ due to chroot) **/
        String serverPath;

        ReplyHeader replyHeader;

        Record request;

        Record response;

        boolean finished;

        AsyncCallback cb;

        Object ctx;

        WatchRegistration watchRegistration;

        Packet(RequestHeader header, ReplyHeader replyHeader, Record record,
               Record response, ByteBuffer bb,
               WatchRegistration watchRegistration) {
            this.header = header;
            this.replyHeader = replyHeader;
            this.request = record;
            this.response = response;
            if (bb != null) {
                this.bb = bb;
            } else {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    BinaryOutputArchive boa = BinaryOutputArchive
                            .getArchive(baos);
                    boa.writeInt(-1, "len"); // We'll fill this in later
                    header.serialize(boa, "header");
                    if (record != null) {
                        record.serialize(boa, "request");
                    }
                    baos.close();
                    this.bb = ByteBuffer.wrap(baos.toByteArray());
                    this.bb.putInt(this.bb.capacity() - 4);
                    this.bb.rewind();
                } catch (IOException e) {
                    LOG.warn("Ignoring unexpected exception", e);
                }
            }
            this.watchRegistration = watchRegistration;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            sb.append("clientPath:" + clientPath);
            sb.append(" serverPath:" + serverPath);
            sb.append(" finished:" + finished);

            sb.append(" header:: " + header);
            sb.append(" replyHeader:: " + replyHeader);
            sb.append(" request:: " + request);
            sb.append(" response:: " + response);

            // jute toString is horrible, remove unnecessary newlines
            return sb.toString().replaceAll("\r*\n+", " ");
        }
    }

    public ClientCnxn(String connectString, int sessionTimeout,ZooKeeper zooKeeper,
                    ClientWatchManager watcher){


    }

    public static boolean getDisableAutoResetWatch(){
        return disableAutoWatchReset;
    }

    /**
     * tests use this to set the auto reset
     * @param b the vaued to set disable watches to
     */
    public static void setDisableAutoResetWatch(boolean b) {
        disableAutoWatchReset = b;
    }
    public void start() {
        sendThread.start();
        eventThread.start();
    }

    final static Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
            LOG.error("from " + t.getName(), e);
        }
    };

    /**
     * 发送线程
     * This class services the outgoing request queue and generates the heart
     * beats. It also spawns the ReadThread.
     */
    class SendThread extends Thread {
        SelectionKey sockKey;

        private final Selector selector = Selector.open();

        final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);

        ByteBuffer incomingBuffer = lenBuffer;

        boolean initialized;

        private long lastPingSentNs;

        long sentCount = 0;
        long recvCount = 0;

        void readLength() throws IOException {
            int len = incomingBuffer.getInt();
            if (len < 0 || len >= packetLen) {
                throw new IOException("Packet len" + len + " is out of range!");
            }
            incomingBuffer = ByteBuffer.allocate(len);
        }

        void readConnectResult() throws IOException {
            ByteBufferInputStream bbis = new ByteBufferInputStream(
                    incomingBuffer);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
            ConnectResponse conRsp = new ConnectResponse();
            conRsp.deserialize(bbia, "connect");
            negotiatedSessionTimeout = conRsp.getTimeOut();
            if (negotiatedSessionTimeout <= 0) {
                zooKeeper.state = ZooKeeper.States.CLOSED;

                eventThread.queueEvent(new WatchedEvent(
                        Watcher.Event.EventType.None,
                        Watcher.Event.KeeperState.Expired, null));
                eventThread.queueEventOfDeath();
                throw new SessionExpiredException(
                        "Unable to reconnect to ZooKeeper service, session 0x"
                                + Long.toHexString(sessionId) + " has expired");
            }
            readTimeout = negotiatedSessionTimeout * 2 / 3;
            connectTimeout = negotiatedSessionTimeout / serverAddrs.size();
            sessionId = conRsp.getSessionId();
            sessionPasswd = conRsp.getPasswd();
            zooKeeper.state = ZooKeeper.States.CONNECTED;
            LOG.info("Session establishment complete on server "
                    + ((SocketChannel)sockKey.channel())
                    .socket().getRemoteSocketAddress()
                    + ", sessionid = 0x"
                    + Long.toHexString(sessionId)
                    + ", negotiated timeout = " + negotiatedSessionTimeout);
            eventThread.queueEvent(new WatchedEvent(Watcher.Event.EventType.None,
                    Watcher.Event.KeeperState.SyncConnected, null));
        }

        /**
         * 当clientCnxnSocketNetty收到服务端响应包时,通过ZKClientHandler的MessageReceived事件来调用readResponse方法
         * 将响应消息填充到Request请求包的response字段上
         * @throws IOException
         */
        void readResponse() throws IOException {
            ByteBufferInputStream bbis = new ByteBufferInputStream(
                    incomingBuffer);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
            ReplyHeader replyHdr = new ReplyHeader();

            replyHdr.deserialize(bbia, "header");
            if (replyHdr.getXid() == -2) {
                // -2 is the xid for pings
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got ping response for sessionid: 0x"
                            + Long.toHexString(sessionId)
                            + " after "
                            + ((System.nanoTime() - lastPingSentNs) / 1000000)
                            + "ms");
                }
                return;
            }
            if (replyHdr.getXid() == -4) {
                // -4 is the xid for AuthPacket
                if(replyHdr.getErr() == KeeperException.Code.AUTHFAILED.intValue()) {
                    zooKeeper.state = ZooKeeper.States.AUTH_FAILED;
                    eventThread.queueEvent( new WatchedEvent(Watcher.Event.EventType.None,
                            Watcher.Event.KeeperState.AuthFailed, null) );
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got auth sessionid:0x"
                            + Long.toHexString(sessionId));
                }
                return;
            }
            if (replyHdr.getXid() == -1) {
                // -1 means notification
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got notification sessionid:0x"
                            + Long.toHexString(sessionId));
                }
                org.apache.zookeeper.proto.WatcherEvent event = new org.apache.zookeeper.proto.WatcherEvent();
                event.deserialize(bbia, "response");

                // convert from a server path to a client path
                if (chrootPath != null) {
                    String serverPath = event.getPath();
                    if(serverPath.compareTo(chrootPath)==0)
                        event.setPath("/");
                    else
                        event.setPath(serverPath.substring(chrootPath.length()));
                }

                WatchedEvent we = new WatchedEvent(event);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got " + we + " for sessionid 0x"
                            + Long.toHexString(sessionId));
                }

                eventThread.queueEvent( we );
                return;
            }
            if (pendingQueue.size() == 0) {
                throw new IOException("Nothing in the queue, but got "
                        + replyHdr.getXid());
            }
            Packet packet = null;
            synchronized (pendingQueue) {
                packet = pendingQueue.remove();
            }
            /*
             * Since requests are processed in order, we better get a response
             * to the first request!
             */
            try {
                if (packet.header.getXid() != replyHdr.getXid()) {
                    packet.replyHeader.setErr(
                            KeeperException.Code.CONNECTIONLOSS.intValue());
                    throw new IOException("Xid out of order. Got "
                            + replyHdr.getXid() + " expected "
                            + packet.header.getXid());
                }

                packet.replyHeader.setXid(replyHdr.getXid());
                packet.replyHeader.setErr(replyHdr.getErr());
                packet.replyHeader.setZxid(replyHdr.getZxid());
                if (replyHdr.getZxid() > 0) {
                    lastZxid = replyHdr.getZxid();
                }
                if (packet.response != null && replyHdr.getErr() == 0) {
                    packet.response.deserialize(bbia, "response");
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Reading reply sessionid:0x"
                            + Long.toHexString(sessionId) + ", packet:: " + packet);
                }
            } finally {
                finishPacket(packet);
            }
        }

        /**
         * @return true if a packet was received
         * @throws InterruptedException
         * @throws IOException
         */
        boolean doIO() throws InterruptedException, IOException {
            boolean packetReceived = false;
            SocketChannel sock = (SocketChannel) sockKey.channel();
            if (sock == null) {
                throw new IOException("Socket is null!");
            }
            if (sockKey.isReadable()) {
                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    throw new EndOfStreamException(
                            "Unable to read additional data from server sessionid 0x"
                                    + Long.toHexString(sessionId)
                                    + ", likely server has closed socket");
                }
                if (!incomingBuffer.hasRemaining()) {
                    incomingBuffer.flip();
                    if (incomingBuffer == lenBuffer) {
                        recvCount++;
                        readLength();
                    } else if (!initialized) {
                        readConnectResult();
                        enableRead();
                        if (!outgoingQueue.isEmpty()) {
                            enableWrite();
                        }
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                        packetReceived = true;
                        initialized = true;
                    } else {
                        readResponse();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                        packetReceived = true;
                    }
                }
            }
            if (sockKey.isWritable()) {
                synchronized (outgoingQueue) {
                    if (!outgoingQueue.isEmpty()) {
                        ByteBuffer pbb = outgoingQueue.getFirst().bb;
                        sock.write(pbb);
                        if (!pbb.hasRemaining()) {
                            sentCount++;
                            Packet p = outgoingQueue.removeFirst();
                            if (p.header != null
                                    && p.header.getType() != ZooDefs.OpCode.ping
                                    && p.header.getType() != ZooDefs.OpCode.auth) {
                                pendingQueue.add(p);
                            }
                        }
                    }
                }
            }
            if (outgoingQueue.isEmpty()) {
                disableWrite();
            } else {
                enableWrite();
            }
            return packetReceived;
        }

        synchronized private void enableWrite() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_WRITE) == 0) {
                sockKey.interestOps(i | SelectionKey.OP_WRITE);
            }
        }

        synchronized private void disableWrite() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_WRITE) != 0) {
                sockKey.interestOps(i & (~SelectionKey.OP_WRITE));
            }
        }

        synchronized private void enableRead() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_READ) == 0) {
                sockKey.interestOps(i | SelectionKey.OP_READ);
            }
        }

        synchronized private void disableRead() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_READ) != 0) {
                sockKey.interestOps(i & (~SelectionKey.OP_READ));
            }
        }

        SendThread() throws IOException {
            super(makeThreadName("-SendThread()"));
            zooKeeper.state = ZooKeeper.States.CONNECTING;
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
        }

        private void primeConnection(SelectionKey k) throws IOException {
            LOG.info("Socket connection established to "
                    + ((SocketChannel)sockKey.channel())
                    .socket().getRemoteSocketAddress()
                    + ", initiating session");
            lastConnectIndex = currentConnectIndex;
            ConnectRequest conReq = new ConnectRequest(0, lastZxid,
                    sessionTimeout, sessionId, sessionPasswd);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            boa.writeInt(-1, "len");
            conReq.serialize(boa, "connect");
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.capacity() - 4);
            bb.rewind();
            synchronized (outgoingQueue) {
                // We add backwards since we are pushing into the front
                // Only send if there's a pending watch
                if (!disableAutoWatchReset) {
                    List<String> dataWatches = zooKeeper.getDataWatches();
                    List<String> existWatches = zooKeeper.getExistWatches();
                    List<String> childWatches = zooKeeper.getChildWatches();
                    if (!dataWatches.isEmpty()
                            || !existWatches.isEmpty() || !childWatches.isEmpty()) {
                        SetWatches sw = new SetWatches(lastZxid,
                                prependChroot(dataWatches),
                                prependChroot(existWatches),
                                prependChroot(childWatches));
                        RequestHeader h = new RequestHeader();
                        h.setType(ZooDefs.OpCode.setWatches);
                        h.setXid(-8);
                        Packet packet = new Packet(h, new ReplyHeader(), sw, null, null,
                                null);
                        outgoingQueue.addFirst(packet);
                    }
                }

                for (AuthData id : authInfo) {
                    outgoingQueue.addFirst(new Packet(new RequestHeader(-4,
                            ZooDefs.OpCode.auth), null, new AuthPacket(0, id.scheme,
                            id.data), null, null, null));
                }
                outgoingQueue.addFirst((new Packet(null, null, null, null, bb,
                        null)));
            }
            synchronized (this) {
                k.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Session establishment request sent on "
                        + ((SocketChannel)sockKey.channel())
                        .socket().getRemoteSocketAddress());
            }
        }

        private List<String> prependChroot(List<String> paths) {
            if (chrootPath != null && !paths.isEmpty()) {
                for (int i = 0; i < paths.size(); ++i) {
                    String clientPath = paths.get(i);
                    String serverPath;
                    // handle clientPath = "/"
                    if (clientPath.length() == 1) {
                        serverPath = chrootPath;
                    } else {
                        serverPath = chrootPath + clientPath;
                    }
                    paths.set(i, serverPath);
                }
            }
            return paths;
        }

        private void sendPing() {
            lastPingSentNs = System.nanoTime();
            RequestHeader h = new RequestHeader(-2, ZooDefs.OpCode.ping);
            queuePacket(h, null, null, null, null, null, null, null, null);
        }

        int lastConnectIndex = -1;

        int currentConnectIndex;

        Random r = new Random(System.nanoTime());

        private void startConnect() throws IOException {
            if (lastConnectIndex == -1) {
                // We don't want to delay the first try at a connect, so we
                // start with -1 the first time around
                lastConnectIndex = 0;
            } else {
                try {
                    Thread.sleep(r.nextInt(1000));
                } catch (InterruptedException e1) {
                    LOG.warn("Unexpected exception", e1);
                }
                if (nextAddrToTry == lastConnectIndex) {
                    try {
                        // Try not to spin too fast!
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        LOG.warn("Unexpected exception", e);
                    }
                }
            }
            zooKeeper.state = ZooKeeper.States.CONNECTING;
            currentConnectIndex = nextAddrToTry;
            InetSocketAddress addr = serverAddrs.get(nextAddrToTry);
            nextAddrToTry++;
            if (nextAddrToTry == serverAddrs.size()) {
                nextAddrToTry = 0;
            }
            LOG.info("Opening socket connection to server " + addr);
            SocketChannel sock;
            sock = SocketChannel.open();
            sock.configureBlocking(false);
            sock.socket().setSoLinger(false, -1);
            sock.socket().setTcpNoDelay(true);
            setName(getName().replaceAll("\\(.*\\)",
                    "(" + addr.getHostName() + ":" + addr.getPort() + ")"));
            try {
                sockKey = sock.register(selector, SelectionKey.OP_CONNECT);
                boolean immediateConnect = sock.connect(addr);
                if (immediateConnect) {
                    primeConnection(sockKey);
                }
            } catch (IOException e) {
                LOG.error("Unable to open socket to " + addr);
                sock.close();
                throw e;
            }
            initialized = false;

            /*
             * Reset incomingBuffer
             */
            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }

        private static final String RETRY_CONN_MSG =
                ", closing socket connection and attempting reconnect";

        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long lastHeard = now;
            long lastSend = now;
            while (zooKeeper.state.isAlive()) {
                try {
                    if (sockKey == null) {
                        // don't re-establish connection if we are closing
                        if (closing) {
                            break;
                        }
                        startConnect();
                        lastSend = now;
                        lastHeard = now;
                    }
                    int idleRecv = (int) (now - lastHeard);
                    int idleSend = (int) (now - lastSend);
                    int to = readTimeout - idleRecv;
                    if (zooKeeper.state != ZooKeeper.States.CONNECTED) {
                        to = connectTimeout - idleRecv;
                    }
                    if (to <= 0) {
                        throw new SessionTimeoutException(
                                "Client session timed out, have not heard from server in "
                                        + idleRecv + "ms"
                                        + " for sessionid 0x"
                                        + Long.toHexString(sessionId));
                    }
                    if (zooKeeper.state == ZooKeeper.States.CONNECTED) {
                        int timeToNextPing = readTimeout/2 - idleSend;
                        if (timeToNextPing <= 0) {
                            sendPing();
                            lastSend = now;
                            enableWrite();
                        } else {
                            if (timeToNextPing < to) {
                                to = timeToNextPing;
                            }
                        }
                    }

                    selector.select(to);
                    Set<SelectionKey> selected;
                    synchronized (this) {
                        selected = selector.selectedKeys();
                    }
                    // Everything below and until we get back to the select is
                    // non blocking, so time is effectively a constant. That is
                    // Why we just have to do this once, here
                    now = System.currentTimeMillis();
                    for (SelectionKey k : selected) {
                        SocketChannel sc = ((SocketChannel) k.channel());
                        if ((k.readyOps() & SelectionKey.OP_CONNECT) != 0) {
                            if (sc.finishConnect()) {
                                lastHeard = now;
                                lastSend = now;
                                primeConnection(k);
                            }
                        } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                            if (outgoingQueue.size() > 0) {
                                // We have something to send so it's the same
                                // as if we do the send now.
                                lastSend = now;
                            }
                            if (doIO()) {
                                lastHeard = now;
                            }
                        }
                    }
                    if (zooKeeper.state == ZooKeeper.States.CONNECTED) {
                        if (outgoingQueue.size() > 0) {
                            enableWrite();
                        } else {
                            disableWrite();
                        }
                    }
                    selected.clear();
                } catch (Throwable e) {
                    if (closing) {
                        if (LOG.isDebugEnabled()) {
                            // closing so this is expected
                            LOG.debug("An exception was thrown while closing send thread for session 0x"
                                    + Long.toHexString(getSessionId())
                                    + " : " + e.getMessage());
                        }
                        break;
                    } else {
                        // this is ugly, you have a better way speak up
                        if (e instanceof SessionExpiredException) {
                            LOG.info(e.getMessage() + ", closing socket connection");
                        } else if (e instanceof SessionTimeoutException) {
                            LOG.info(e.getMessage() + RETRY_CONN_MSG);
                        } else if (e instanceof EndOfStreamException) {
                            LOG.info(e.getMessage() + RETRY_CONN_MSG);
                        } else {
                            LOG.warn("Session 0x"
                                            + Long.toHexString(getSessionId())
                                            + " for server "
                                            + ((SocketChannel)sockKey.channel())
                                            .socket().getRemoteSocketAddress()
                                            + ", unexpected error"
                                            + RETRY_CONN_MSG,
                                    e);
                        }
                        cleanup();
                        if (zooKeeper.state.isAlive()) {
                            eventThread.queueEvent(new WatchedEvent(
                                    Watcher.Event.EventType.None,
                                    Watcher.Event.KeeperState.Disconnected,
                                    null));
                        }

                        now = System.currentTimeMillis();
                        lastHeard = now;
                        lastSend = now;
                    }
                }
            }
            cleanup();
            try {
                selector.close();
            } catch (IOException e) {
                LOG.warn("Ignoring exception during selector close", e);
            }
            if (zooKeeper.state.isAlive()) {
                eventThread.queueEvent(new WatchedEvent(
                        Watcher.Event.EventType.None,
                        Watcher.Event.KeeperState.Disconnected,
                        null));
            }
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                    "SendThread exitedloop.");
        }

        private void cleanup() {
            if (sockKey != null) {
                SocketChannel sock = (SocketChannel) sockKey.channel();
                sockKey.cancel();
                try {
                    sock.socket().shutdownInput();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during shutdown input", e);
                    }
                }
                try {
                    sock.socket().shutdownOutput();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during shutdown output", e);
                    }
                }
                try {
                    sock.socket().close();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during socket close", e);
                    }
                }
                try {
                    sock.close();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during channel close", e);
                    }
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("SendThread interrupted during sleep, ignoring");
                }
            }
            sockKey = null;
            synchronized (pendingQueue) {
                for (Packet p : pendingQueue) {
                    conLossPacket(p);
                }
                pendingQueue.clear();
            }
            synchronized (outgoingQueue) {
                for (Packet p : outgoingQueue) {
                    conLossPacket(p);
                }
                outgoingQueue.clear();
            }
        }

        public void close() {
            synchronized (this) {
                zooKeeper.state = ZooKeeper.States.CLOSED;
                selector.wakeup();
            }
        }

        synchronized private void wakeup() {
            selector.wakeup();
        }
    }

    /**
     * Guard against creating "-EventThread-EventThread-EventThread-..." thread
     * names when ZooKeeper object is being created from within a watcher.
     * See ZOOKEEPER-795 for details.
     */
    private static String makeThreadName(String suffix) {
        String name = Thread.currentThread().getName().
                replaceAll("-EventThread", "");
        return name + suffix;
    }

    /**
     * 事件处理线程
     * 处理server返回的watchedevent*
     */
    class EventThread extends Thread {
        private final LinkedBlockingQueue<Object> waitingEvents =
                new LinkedBlockingQueue<Object>();

        /** This is really the queued session state until the event
         * thread actually processes the event and hands it to the watcher.
         * But for all intents and purposes this is the state.
         */
        private volatile KeeperState sessionState = KeeperState.Disconnected;

        private volatile boolean wasKilled = false;
        private volatile boolean isRunning = false;

        EventThread() {
            super(makeThreadName("-EventThread"));
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
        }

        public void queueEvent(WatchedEvent event) {
            if (event.getType() == EventType.None
                    && sessionState == event.getState()) {
                return;
            }
            sessionState = event.getState();

            // materialize the watchers based on the event
            WatcherSetEventPair pair = new WatcherSetEventPair(
                    watcher.materialize(event.getState(), event.getType(),
                            event.getPath()),
                    event);
            // queue the pair (watch set & event) for later processing
            waitingEvents.add(pair);
        }

        public void queuePacket(Packet packet) {
            if (wasKilled) {
                synchronized (waitingEvents) {
                    if (isRunning) waitingEvents.add(packet);
                    else processEvent(packet);
                }
            } else {
                waitingEvents.add(packet);
            }
        }

        public void queueEventOfDeath() {
            waitingEvents.add(eventOfDeath);
        }

        @Override
        public void run() {
            try {
                isRunning = true;
                while (true) {
                    Object event = waitingEvents.take();
                    if (event == eventOfDeath) {
                        wasKilled = true;
                    } else {
                        processEvent(event);
                    }
                    if (wasKilled)
                        synchronized (waitingEvents) {
                            if (waitingEvents.isEmpty()) {
                                isRunning = false;
                                break;
                            }
                        }
                }
            } catch (InterruptedException e) {
                LOG.error("Event thread exiting due to interruption", e);
            }

            LOG.info("EventThread shut down");
        }

        private void processEvent(Object event) {
            try {
                if (event instanceof WatcherSetEventPair) {
                    // each watcher will process the event
                    WatcherSetEventPair pair = (WatcherSetEventPair) event;
                    for (Watcher watcher : pair.watchers) {
                        try {
                            watcher.process(pair.event);
                        } catch (Throwable t) {
                            LOG.error("Error while calling watcher ", t);
                        }
                    }
                } else {
                    Packet p = (Packet) event;
                    int rc = 0;
                    String clientPath = p.clientPath;
                    if (p.replyHeader.getErr() != 0) {
                        rc = p.replyHeader.getErr();
                    }
                    if (p.cb == null) {
                        LOG.warn("Somehow a null cb got to EventThread!");
                    } else if (p.response instanceof ExistsResponse
                            || p.response instanceof SetDataResponse
                            || p.response instanceof SetACLResponse) {
                        StatCallback cb = (StatCallback) p.cb;
                        if (rc == 0) {
                            if (p.response instanceof ExistsResponse) {
                                cb.processResult(rc, clientPath, p.ctx,
                                        ((ExistsResponse) p.response)
                                                .getStat());
                            } else if (p.response instanceof SetDataResponse) {
                                cb.processResult(rc, clientPath, p.ctx,
                                        ((SetDataResponse) p.response)
                                                .getStat());
                            } else if (p.response instanceof SetACLResponse) {
                                cb.processResult(rc, clientPath, p.ctx,
                                        ((SetACLResponse) p.response)
                                                .getStat());
                            }
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null);
                        }
                    } else if (p.response instanceof GetDataResponse) {
                        DataCallback cb = (DataCallback) p.cb;
                        GetDataResponse rsp = (GetDataResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getData(), rsp.getStat());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null,
                                    null);
                        }
                    } else if (p.response instanceof GetACLResponse) {
                        ACLCallback cb = (ACLCallback) p.cb;
                        GetACLResponse rsp = (GetACLResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getAcl(), rsp.getStat());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null,
                                    null);
                        }
                    } else if (p.response instanceof GetChildrenResponse) {
                        ChildrenCallback cb = (ChildrenCallback) p.cb;
                        GetChildrenResponse rsp = (GetChildrenResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getChildren());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null);
                        }
                    } else if (p.response instanceof GetChildren2Response) {
                        Children2Callback cb = (Children2Callback) p.cb;
                        GetChildren2Response rsp = (GetChildren2Response) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getChildren(), rsp.getStat());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null, null);
                        }
                    } else if (p.response instanceof CreateResponse) {
                        StringCallback cb = (StringCallback) p.cb;
                        CreateResponse rsp = (CreateResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx,
                                    (chrootPath == null
                                            ? rsp.getPath()
                                            : rsp.getPath()
                                            .substring(chrootPath.length())));
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null);
                        }
                    } else if (p.cb instanceof VoidCallback) {
                        VoidCallback cb = (VoidCallback) p.cb;
                        cb.processResult(rc, clientPath, p.ctx);
                    }
                }
            } catch (Throwable t) {
                LOG.error("Caught unexpected throwable", t);
            }
        }
    }


    private void finishPacket(Packet p) {
        if (p.watchRegistration != null) {
            p.watchRegistration.register(p.replyHeader.getErr());
        }

        if (p.cb == null) {
            synchronized (p) {
                p.finished = true;
                p.notifyAll();
            }
        } else {
            p.finished = true;
            eventThread.queuePacket(p);
        }
    }


    private void conLossPacket(Packet p) {
        if (p.replyHeader == null) {
            return;
        }
        switch(zooKeeper.state) {
            case AUTH_FAILED:
                p.replyHeader.setErr(KeeperException.Code.AUTHFAILED.intValue());
                break;
            case CLOSED:
                p.replyHeader.setErr(KeeperException.Code.SESSIONEXPIRED.intValue());
                break;
            default:
                p.replyHeader.setErr(KeeperException.Code.CONNECTIONLOSS.intValue());
        }
        finishPacket(p);
    }

    private static class SessionExpiredException extends IOException {
        private static final long serialVersionUID = -1388816932076193249L;

        public SessionExpiredException(String msg) {
            super(msg);
        }
    }

    private static class EndOfStreamException extends IOException {
        private static final long serialVersionUID = -5438877188796231422L;

        public EndOfStreamException(String msg) {
            super(msg);
        }

        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

    private static class SessionTimeoutException extends IOException {
        private static final long serialVersionUID = 824482094072071178L;

        public SessionTimeoutException(String msg) {
            super(msg);
        }
    }

    private int xid = 1;

    synchronized private int getXid() {
        return xid++;
    }

    public ReplyHeader submitRequest(RequestHeader h, Record request,
                                     Record response, WatchRegistration watchRegistration)
            throws InterruptedException {
        ReplyHeader r = new ReplyHeader();
        Packet packet = queuePacket(h, r, request, response, null, null, null,
                null, watchRegistration);
        synchronized (packet) {
            while (!packet.finished) {
                packet.wait();
            }
        }
        return r;
    }

    Packet queuePacket(RequestHeader h, ReplyHeader r, Record request,
                       Record response, AsyncCallback cb, String clientPath,
                       String serverPath, Object ctx, WatchRegistration watchRegistration)
    {
        Packet packet = null;
        synchronized (outgoingQueue) {
            if (h.getType() != ZooDefs.OpCode.ping && h.getType() != ZooDefs.OpCode.auth) {
                h.setXid(getXid());
            }
            packet = new Packet(h, r, request, response, null,
                    watchRegistration);
            packet.cb = cb;
            packet.ctx = ctx;
            packet.clientPath = clientPath;
            packet.serverPath = serverPath;
            if (!zooKeeper.state.isAlive() || closing) {
                conLossPacket(packet);
            } else {
                // If the client is asking to close the session then
                // mark as closing
                if (h.getType() == ZooDefs.OpCode.closeSession) {
                    closing = true;
                }
                outgoingQueue.add(packet);
            }
        }

        sendThread.wakeup();
        return packet;
    }

    public void addAuthInfo(String scheme, byte auth[]) {
        if (!zooKeeper.state.isAlive()) {
            return;
        }
        authInfo.add(new AuthData(scheme, auth));
        queuePacket(new RequestHeader(-4, OpCode.auth), null,
                new AuthPacket(0, scheme, auth), null, null, null, null,
                null, null);
    }

}
