package com.ctu.zookeeper;


import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;

import java.io.IOException;
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


    public ZooKeeper connect(String host, Watcher watcher)throws IOException,InterruptedException,KeeperException {
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

    /**
     * 删除znode
     * @param path
     * @throws InterruptedException
     * @throws KeeperException
     */
    public void delete(String path)throws InterruptedException,KeeperException{
        List<String> children = zk.getChildren(path, false);
        for (String pathCd : children) {
            //获取父节点下面的子节点路径
            String newPath = "";
            //递归调用,判断是否是根节点
            if (path.equals("/")) {
                newPath = "/" + pathCd;
            } else {
                newPath = path + "/" + pathCd;
            }
            delete(newPath);
        }
        //删除节点,并过滤zookeeper节点和 /节点
        if (path != null && !path.trim().startsWith("/zookeeper") && !path.trim().equals("/")) {
            zk.delete(path, -1);
            //打印删除的节点路径
            System.out.println("被删除的节点为：" + path);
        }
    }

    public List<String> getChilds(String path)throws InterruptedException,KeeperException{
        return zk.getChildren(path,true);
    }

    public List<ACL> getacl(String path)throws InterruptedException,KeeperException{
        return zk.getACL(path,zk.exists(path,true));
    }

    public void close() throws InterruptedException{
        zk.close();

    }
}
