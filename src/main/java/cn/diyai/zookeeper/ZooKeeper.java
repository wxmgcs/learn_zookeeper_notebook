package cn.diyai.zookeeper;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by diyai on 2018/2/9.
 */
public class ZooKeeper {
    private static final Logger LOG = Logger.getLogger(ZooKeeper.class);
    protected final ClientCnxn cnxn;
    private final ZKWatchManager watchManager = new ZKWatchManager();

    public ZooKeeper(String connectString,int sessionTimeout,Watcher watcher) throws IOException{
        LOG.info("Initiating client connection, connectString=" + connectString
                + " sessionTimeout=" + sessionTimeout + " watcher=" + watcher);
        watchManager.defaultWatcher = watcher;
        cnxn = new ClientCnxn(connectString, sessionTimeout, this, watchManager);
        cnxn.start();
    }

    public enum States {
        CONNECTING, ASSOCIATING, CONNECTED, CLOSED, AUTH_FAILED;

        public boolean isAlive() {
            return this != CLOSED && this != AUTH_FAILED;
        }
    }

    //如果读操作远远大于写操作，volatile 变量还可以提供优于锁的性能优势。
    volatile States state;


    public States getState(){
        return state;

    }

    public void close(){


    }

    private static class ZKWatchManager implements ClientWatchManager{
        private final Map<String, Set<Watcher>> dataWatches =
                new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> existWatches =
                new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> childWatches =
                new HashMap<String, Set<Watcher>>();

        private volatile Watcher defaultWatcher;

        final private void addTo(Set<Watcher> from, Set<Watcher> to) {
            if (from != null) {
                to.addAll(from);
            }
        }

        public Set<Watcher> materialize(Watcher.Event.KeeperState state, Watcher.Event.EventType type, String clientPath){

            Set<Watcher> result = new HashSet<Watcher>();

            switch (type) {
                case None:
                    result.add(defaultWatcher);
                    for(Set<Watcher> ws: dataWatches.values()) {
                        result.addAll(ws);
                    }
                    for(Set<Watcher> ws: existWatches.values()) {
                        result.addAll(ws);
                    }
                    for(Set<Watcher> ws: childWatches.values()) {
                        result.addAll(ws);
                    }

                    // clear the watches if auto watch reset is not enabled
                    if (ClientCnxn.getDisableAutoResetWatch() &&
                            state != Watcher.Event.KeeperState.SyncConnected)
                    {
                        synchronized(dataWatches) {
                            dataWatches.clear();
                        }
                        synchronized(existWatches) {
                            existWatches.clear();
                        }
                        synchronized(childWatches) {
                            childWatches.clear();
                        }
                    }

                    return result;
                case NodeDataChanged:
                case NodeCreated:
                    synchronized (dataWatches) {
                        addTo(dataWatches.remove(clientPath), result);
                    }
                    synchronized (existWatches) {
                        addTo(existWatches.remove(clientPath), result);
                    }
                    break;
                case NodeChildrenChanged:
                    synchronized (childWatches) {
                        addTo(childWatches.remove(clientPath), result);
                    }
                    break;
                case NodeDeleted:
                    synchronized (dataWatches) {
                        addTo(dataWatches.remove(clientPath), result);
                    }
                    // XXX This shouldn't be needed, but just in case
                    synchronized (existWatches) {
                        Set<Watcher> list = existWatches.remove(clientPath);
                        if (list != null) {
                            addTo(existWatches.remove(clientPath), result);
                            LOG.warn("We are triggering an exists watch for delete! Shouldn't happen!");
                        }
                    }
                    synchronized (childWatches) {
                        addTo(childWatches.remove(clientPath), result);
                    }
                    break;
                default:
                    String msg = "Unhandled watch event type " + type
                            + " with state " + state + " on path " + clientPath;
                    LOG.error(msg);
                    throw new RuntimeException(msg);
            }

            return result;
        }
    }

    /**
     * Register a watcher for a particular path.
     */
    abstract class WatchRegistration {
        private Watcher watcher;
        private String clientPath;
        public WatchRegistration(Watcher watcher, String clientPath)
        {
            this.watcher = watcher;
            this.clientPath = clientPath;
        }

        abstract protected Map<String, Set<Watcher>> getWatches(int rc);

        /**
         * Register the watcher with the set of watches on path.
         * @param rc the result code of the operation that attempted to
         * add the watch on the path.
         */
        public void register(int rc) {
            if (shouldAddWatch(rc)) {
                Map<String, Set<Watcher>> watches = getWatches(rc);
                synchronized(watches) {
                    Set<Watcher> watchers = watches.get(clientPath);
                    if (watchers == null) {
                        watchers = new HashSet<Watcher>();
                        watches.put(clientPath, watchers);
                    }
                    watchers.add(watcher);
                }
            }
        }
        /**
         * Determine whether the watch should be added based on return code.
         * @param rc the result code of the operation that attempted to add the
         * watch on the node
         * @return true if the watch should be added, otw false
         */
        protected boolean shouldAddWatch(int rc) {
            return rc == 0;
        }
    }
}
