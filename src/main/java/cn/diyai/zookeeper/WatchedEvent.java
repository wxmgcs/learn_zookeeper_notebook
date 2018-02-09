package cn.diyai.zookeeper;



/**
 * Created by diyai on 2018/2/9.
 */
public class WatchedEvent {
    final private Watcher.Event.KeeperState keeperState;
    final private Watcher.Event.EventType eventType;
    private String path;
    /**
     * Create a WatchedEvent with specified type, state and path
     */
    public WatchedEvent(Watcher.Event.EventType eventType, Watcher.Event.KeeperState keeperState, String path) {
        this.keeperState = keeperState;
        this.eventType = eventType;
        this.path = path;
    }

    /**
     * Convert a WatcherEvent sent over the wire into a full-fledged WatcherEvent
     */
    public WatchedEvent(WatcherEvent eventMessage) {
        keeperState = Watcher.Event.KeeperState.fromInt(eventMessage.getState());
        eventType = Watcher.Event.EventType.fromInt(eventMessage.getType());
        path = eventMessage.getPath();
    }

    public Watcher.Event.KeeperState getState() {
        return keeperState;
    }

    public Watcher.Event.EventType getType() {
        return eventType;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "[diyai] WatchedEvent state:" + keeperState
                + " type:" + eventType + " path:" + path;
    }

    /**
     *  Convert WatchedEvent to type that can be sent over network
     */
    public WatcherEvent getWrapper() {
        return new WatcherEvent(eventType.getIntValue(),
                keeperState.getIntValue(),
                path);
    }
}
