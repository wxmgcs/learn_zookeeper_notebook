import cn.diyai.zookeeper.KeeperException;
import cn.diyai.zookeeper.ZooKeeper;
import com.ctu.zookeeper.CommandOptions;
import junit.framework.TestCase;

import java.io.IOException;

/**
 * Created by diyai on 2018/2/9.
 */
public class TestCommandOptions extends TestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }


    public void test(){
        CommandOptions cl = new CommandOptions();

        String[] args = new String[]{"-server","localhost","-timeout","30000","ls","/test"};
        cl.parseOptions(args);
        assertEquals(cl.getOption("server"),"localhost");

    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }
}
