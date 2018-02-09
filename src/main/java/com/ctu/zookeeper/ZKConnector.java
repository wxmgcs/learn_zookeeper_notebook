package com.ctu.zookeeper;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.zookeeper.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Created by diyai on 2018/2/9.
 */
public class ZKConnector {

    private ZooKeeper zk;
    private CountDownLatch downLatch = new CountDownLatch(1);

    private Watcher watcher = new Watcher() {
        public void process(WatchedEvent event) {
            System.out.println(event.toString());
            if(event.getState() == Event.KeeperState.SyncConnected){
                downLatch.countDown();
            }
        }
    };


    public ZooKeeper connect(String host,Watcher watcher)throws IOException,InterruptedException,KeeperException{
        zk = new ZooKeeper(host, Constant.SESSION_TIMEOUT, watcher);
        return zk;

    }

    public ZooKeeper connect(String host)throws IOException,InterruptedException,KeeperException{
        zk = new ZooKeeper(host, Constant.SESSION_TIMEOUT, watcher);
        return zk;

    }

    public void update(String path,byte[] data) throws InterruptedException,KeeperException{
        zk.setData(path,data,zk.exists(path,true).getVersion());

    }

    public byte[] getData(String path)throws InterruptedException,KeeperException{
        return zk.getData(path,true,zk.exists(path,true));
    }

    public void setData(String path,byte[] data)throws InterruptedException,KeeperException{
        zk.setData(path,data,zk.exists(path,true).getVersion());

    }

    public boolean create(String path,byte[] data)throws InterruptedException,KeeperException{
        try {
            zk.create(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            return true;
        }catch (Exception ex){
            if(ex.getMessage().contains("NodeExists")){
                return true;
            }
        }
        return false;
    }

    public List<String> getChilds(String path)throws InterruptedException,KeeperException{
        return zk.getChildren(path,true);
    }

    public void close() throws InterruptedException{
        zk.close();

    }
}
