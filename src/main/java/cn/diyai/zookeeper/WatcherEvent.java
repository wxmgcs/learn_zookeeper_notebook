package cn.diyai.zookeeper;

import cn.diyai.jute.InputArchive;
import cn.diyai.jute.OutputArchive;
import cn.diyai.jute.Record;

import java.io.IOException;

/**
 * Created by diyai on 2018/2/9.
 */
public class WatcherEvent implements Record {
    private int state;
    private int type;
    private String path;

    public int getState() {
        return state;
    }

    public int getType(){
        return type;
    }

    public String getPath(){
        return path;

    }

    public WatcherEvent(int state,int type,String path){


    }

    public void serialize(OutputArchive archive, String tag)
            throws IOException{


    }
    public void deserialize(InputArchive archive, String tag)
            throws IOException{


    }
}
