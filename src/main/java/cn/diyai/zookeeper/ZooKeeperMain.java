package cn.diyai.zookeeper;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.*;

/**
 * Created by diyai on 2018/2/9.
 */
public class ZooKeeperMain {
    private static final Logger LOG = Logger.getLogger(ZooKeeperMain.class);
    MyCommandOptions cl = new MyCommandOptions();
    protected ZooKeeper zk;


    public ZooKeeperMain(String[] args) throws  IOException,InterruptedException,KeeperException {
        cl.parseOptions(args);
        System.out.println("Connecting to " + cl.getOption("server"));
        connectToZK(cl.getOption("server"));
    }

    protected void connectToZK(String newHost) throws IOException,InterruptedException,KeeperException{
        if (zk != null && zk.getState().isAlive()) {
            zk.close();
        }
        zk = new ZooKeeper(newHost,Integer.parseInt(cl.getOption("timeout")),new MyWatcher());

    }

    void run(){


    }



    public  static void main(String[] args) throws  IOException,InterruptedException,KeeperException{
        ZooKeeperMain main = new ZooKeeperMain(args);
        main.run();

    }

    private class MyWatcher implements Watcher {
        public void process(WatchedEvent event) {
            printMessage("WATCHER::");
            printMessage(event.toString());
        }
    }

    private class MyCommandOptions{
        private Map<String,String> options = new HashMap<String, String>();
        private List<String> cmdArgs = null;
        private String command = null;

        public int getNumArguments(){
            return cmdArgs.size();
        }

        public String getCmdArgument(int index){
            return cmdArgs.get(index);
        }

        public String getCommand(){
            return command;
        }

        public String getOption(String opt) {
            return options.get(opt);
        }

        public MyCommandOptions(){
            options.put("server","localhost:2181");
            options.put("timeout","30000");
        }

        public String[] getArgArray(){
            return cmdArgs.toArray(new String[0]);
        }

        /**
         *  格式是-server xxx -timeout xxx cmd...
         * @param args
         * @return
         */
        public boolean parseOptions(String[] args){
            List<String> argList = Arrays.asList(args);
            Iterator<String> it = argList.iterator();
            while(it.hasNext()){
                String opt = it.next();
                try{
                    if(opt.equals("-server")){
                        options.put("server",it.next());
                    }else if(opt.equals("-timeout")){
                        options.put("timeout",it.next());
                    }
                }catch(Exception ex){
                    System.out.println("Error:no argument found for option "+opt);
                    return false;
                }

                if(!opt.startsWith("-")){
                    command = opt;
                    cmdArgs = new ArrayList<String>();
                    cmdArgs.add(command);
                    while(it.hasNext()){
                        cmdArgs.add(it.next());
                    }
                    return true;
                }
            }
            return true;
        }

        /**
         * 解析输入的命令参数
         * @param cmdstring
         * @return
         */
        public boolean parseCommand(String cmdstring){
            String[] args = cmdstring.split(" ");
            if(args.length == 0){
                return false;
            }

            command = args[0];
            cmdArgs = Arrays.asList(args);
            return true;
        }
    }

    public static void printMessage(String msg) {
        System.out.println("\n"+msg);
    }
}
