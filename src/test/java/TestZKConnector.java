import com.ctu.zookeeper.Constant;
import com.ctu.zookeeper.ZKConnector;
import junit.framework.TestCase;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;

/**
 * Created by diyai on 2018/2/9.
 */
public class TestZKConnector extends TestCase{

    ZKConnector zkc = null;
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        zkc  = new ZKConnector();
    }

    public void testCreate() throws IOException,InterruptedException,KeeperException{
        String path = "/test";
        zkc.connect(Constant.CONNECTION_STRING);
        String data = "this is test znode";

        // 创建znode
        zkc.create(path,data.getBytes());

        for(byte item:zkc.getData(path)){
            System.out.print((char)item);
        }

        assertEquals(new String(zkc.getData(path)),data);

    }


    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        zkc.close();
    }
}
