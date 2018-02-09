# learn_zookeeper_notebook
学习zookeeper笔记

## 服务发现
    1.启动ServiceDiscovery.java
    2.bin/zkCli.sh 
    3.执行
```
create /zoo zoo
```
此时会输出
zoo
zookeeper
================



#### WatchZnodeTest


##ACL
    31=16+8+4+2+1 = cdrwa
    read => 2^0
    write => 2^1
    create => 2^2
    delete => 2^3
    admin => 2^4

    [zk: localhost:2181(CONNECTED) 13] getAcl /test
    'world,'anyone
    : cdrwa
    [zk: localhost:2181(CONNECTED) 14] setAcl /test world:anyone:crad
    cZxid = 0x6b
    ctime = Fri Feb 09 14:18:38 CST 2018
    mZxid = 0x6b
    mtime = Fri Feb 09 14:18:38 CST 2018
    pZxid = 0x6b
    cversion = 0
    dataVersion = 0
    aclVersion = 1
    ephemeralOwner = 0x0
    dataLength = 18
    numChildren = 0
    [zk: localhost:2181(CONNECTED) 15] getAcl /test                  
    'world,'anyone
    : cdra
    
    