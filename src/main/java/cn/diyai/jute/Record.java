package cn.diyai.jute;

import java.io.IOException;

/**
 * Created by diyai on 2018/2/9.
 */
public interface Record {
    public void serialize(OutputArchive archive, String tag)
            throws IOException;
    public void deserialize(InputArchive archive, String tag)
            throws IOException;
}
