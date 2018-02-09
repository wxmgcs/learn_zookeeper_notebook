package cn.diyai.zookeeper;

import java.util.Set;

/**
 * Watcher事件管理
 * Created by diyai on 2018/2/9.
 */
public interface ClientWatchManager {
    public Set<Watcher> materialize(Watcher.Event.KeeperState state, Watcher.Event.EventType type, String path);
}
