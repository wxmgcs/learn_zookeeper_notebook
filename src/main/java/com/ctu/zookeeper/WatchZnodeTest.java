package com.ctu.zookeeper;

import org.apache.zookeeper.*;

import java.io.IOException;
import java.util.Scanner;

import static com.ctu.zookeeper.Constant.CONNECTION_STRING;
import static com.ctu.zookeeper.Constant.CONNECTION_TIMEOUT;

/**
 * @author zhaojh.
 */
public class WatchZnodeTest {
    private static final String ZK_SERVICE_REGISTRY_PATH = "/serviceRegistry";

    private static void watchServiceRegistryZnode(final ZooKeeper zk){
        try {
            zk.exists(ZK_SERVICE_REGISTRY_PATH, new Watcher() {
                public void process(WatchedEvent event) {
                    String message = String.format("在节点[%s]上触发事件[%s]", event.getPath(), event.getType());
                    System.out.println(message);

                    watchServiceRegistryZnode(zk);
                }
            });
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    public static void main(String[] args) throws IOException, KeeperException, InterruptedException {

        ZKConnector zkc = new ZKConnector();
        ZooKeeper zk  = zkc.connect(Constant.CONNECTION_STRING);
        zkc.create(ZK_SERVICE_REGISTRY_PATH,"serviceRegistry".getBytes());

        watchServiceRegistryZnode(zk);

        Scanner scanner = new Scanner(System.in);
        System.out.println("请输入'quit'结束程序:");
        while (true){
            String commond = scanner.nextLine();
            if(commond.equalsIgnoreCase("quit")){
                System.out.println("bye!");
                break;
            }
            System.out.println(">>" + commond);
        }

        zk.close();
    }
}
