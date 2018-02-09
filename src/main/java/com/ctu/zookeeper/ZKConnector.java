package com.ctu.zookeeper;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by diyai on 2018/2/9.
 */
public class ZKConnector {

    private static final Logger logger = LogManager.getLogger("HelloWorld");
    private static ZooKeeper zk;

    private java.util.concurrent.CountDownLatch connSignal = new java.util.concurrent.CountDownLatch(1);

    private static List<String> znodeList = new ArrayList<String>();


    public static void main(String[] args)throws IOException,InterruptedException,KeeperException{
        zk = new ZKConnector().connect("localhost");
        List<String> znodeList = zk.getChildren("/", true);
        for(String node:znodeList){
            logger.info(node);
        }

    }

    public ZooKeeper connect(String host) throws IOException{
        return new ZooKeeper(host, 3000, new Watcher() {
            public void process(WatchedEvent watchedEvent) {
                if(watchedEvent.getState() == Event.KeeperState.SyncConnected){
                    connSignal.countDown();
                }
            }
        });

    }

    private void close() throws InterruptedException{
        zk.close();

    }
}
