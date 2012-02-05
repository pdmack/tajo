/**
 * 
 */
package nta.engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import nta.catalog.CatalogService;
import nta.catalog.LocalCatalog;
import nta.catalog.TableDesc;
import nta.catalog.TableDescImpl;
import nta.catalog.TableMeta;
import nta.catalog.TableUtil;
import nta.catalog.exception.AlreadyExistsTableException;
import nta.catalog.exception.NoSuchTableException;
import nta.conf.NtaConf;
import nta.engine.exception.NTAQueryException;
import nta.engine.ipc.QueryEngineInterface;
import nta.engine.query.GlobalEngine;
import nta.rpc.NettyRpc;
import nta.rpc.ProtoParamRpcServer;
import nta.storage.StorageManager;
import nta.zookeeper.ZkClient;
import nta.zookeeper.ZkServer;
import nta.zookeeper.ZkUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.net.NetUtils;
import org.apache.zookeeper.KeeperException;

/**
 * @author Hyunsik Choi
 * 
 */
public class NtaEngineMaster extends Thread implements QueryEngineInterface {
  private static final Log LOG = LogFactory.getLog(NtaEngineMaster.class);

  private final Configuration conf;
  private FileSystem defaultFS;

  private volatile boolean stopped = false;

  private final String serverName;
  private final ZkClient zkClient;
  private ZkServer zkServer;

  private CatalogService catalog;
  private StorageManager storeManager;
  private GlobalEngine queryEngine;

  private final Path basePath;
  private final Path dataPath;

  private final InetSocketAddress bindAddr;
  private ProtoParamRpcServer server; // RPC between master and client

  private List<EngineService> services = new ArrayList<EngineService>();

  public NtaEngineMaster(final Configuration conf) throws Exception {
    this.conf = conf;

    // Get the tajo base dir
    this.basePath = new Path(conf.get(NConstants.ENGINE_BASE_DIR));
    LOG.info("Base dir is set " + conf.get(NConstants.ENGINE_BASE_DIR));
    // Get default DFS uri from the base dir
    this.defaultFS = basePath.getFileSystem(conf);
    LOG.info("FileSystem (" + this.defaultFS.getUri() + ") is initialized.");    
        
    if (defaultFS.exists(basePath) == false) {
      defaultFS.mkdirs(basePath);
      LOG.info("Tajo Base dir (" + basePath + ") is created.");
    }

    this.dataPath = new Path(conf.get(NConstants.ENGINE_DATA_DIR));
    LOG.info("Tajo data dir is set " + dataPath);
    if (!defaultFS.exists(dataPath)) {
      defaultFS.mkdirs(dataPath);
      LOG.info("Data dir (" + dataPath + ") is created");
    }
    
    this.storeManager = new StorageManager(conf);

    this.queryEngine = new GlobalEngine(conf, catalog, storeManager);
    this.queryEngine.init();
    services.add(queryEngine);
    
    // The below is some mode-dependent codes
    // If tajo is local mode
    if (conf.get(NConstants.CLUSTER_DISTRIBUTED, 
        NConstants.CLUSTER_IS_LOCAL).equals("false")) {
      this.zkServer = new ZkServer(conf);
      this.zkServer.start();
      
      // TODO - When the RPC framework supports all methods of the catalog 
      // server, the below comments should be eliminated.
      //this.catalog = new LocalCatalog(conf);
    } else { // if tajo is distributed mode
      
      // connect to the catalog server
      //this.catalog = new CatalogClient(conf);
    }
    // This is temporal solution of the above problem.
    this.catalog = new LocalCatalog(conf);

    // connect the zkserver
    this.zkClient = new ZkClient(conf);

    // Setup RPC server
    // Get the master address
    String masterAddr =
        conf.get(NConstants.MASTER_ADDRESS, NConstants.DEFAULT_MASTER_ADDRESS);
    InetSocketAddress initIsa = 
        NetUtils.createSocketAddr(masterAddr);
    this.server = NettyRpc.getProtoParamRpcServer(this, initIsa);
    this.server.start();
    this.bindAddr = this.server.getBindAddress();    
    this.serverName = bindAddr.getHostName() + ":" + bindAddr.getPort();
    LOG.info(NtaEngineMaster.class.getSimpleName() + " is bind to "
        + serverName);
    this.conf.set(NConstants.MASTER_ADDRESS, this.serverName);
    Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHook()));
  }
  
  private void initMaster() throws IOException, KeeperException, 
      InterruptedException {    
    becomeMaster();
  }
  
  private void becomeMaster() throws IOException, KeeperException,
      InterruptedException {
    ZkUtil.createPersistentNodeIfNotExist(zkClient, NConstants.ZNODE_BASE);
    ZkUtil.upsertEphemeralNode(zkClient, NConstants.ZNODE_MASTER, 
        serverName.getBytes());
    ZkUtil.createPersistentNodeIfNotExist(zkClient, 
        NConstants.ZNODE_LEAFSERVERS);
    ZkUtil.createPersistentNodeIfNotExist(zkClient, NConstants.ZNODE_QUERIES);
  }

  public void run() {
    LOG.info("NtaEngineMaster startup");
    try {
      initMaster();
      
      if (!this.stopped) {
        while (!this.stopped) {
          Thread.sleep(1000);
        }
      }
    } catch (Throwable t) {
      LOG.fatal("Unhandled exception. Starting shutdown.", t);
    } finally {
      // TODO - adds code to stop all services and clean resources
    }

    LOG.info("NtaEngineMaster main thread exiting");
  }

  public String getServerName() {
    return this.serverName;
  }

  public InetSocketAddress getRpcServerAddr() {
    return this.bindAddr;
  }

  public boolean isMasterRunning() {
    return !this.stopped;
  }

  public void shutdown() {
    this.stopped = true;
    this.server.shutdown();

    for (EngineService service : services) {
      try {
        service.shutdown();
      } catch (Exception e) {
        LOG.error(e);
      }
    }
  }

  public List<String> getOnlineServer() throws KeeperException,
      InterruptedException {
    return zkClient.getChildren(NConstants.ZNODE_LEAFSERVERS);
  }

  public static void main(String[] args) throws Exception {
    NtaConf conf = new NtaConf();
    NtaEngineMaster master = new NtaEngineMaster(conf);

    master.start();
  }

  @Override
  public String executeQuery(String query) {
    // TODO Auto-generated method stub
    return "Path String should be returned";
  }

  @Override
  public String executeQueryAsync(String query) {
    // TODO Auto-generated method stub
    return "Path String should be returned(Async)";
  }

  @Override
  @Deprecated
  public void createTable(TableDescImpl meta) {
    // TODO Auto-generated method stub

  }

  @Override
  @Deprecated
  public void dropTable(String name) {

  }

  @Deprecated
  @Override
  public void attachTable(String name, Path path) throws Exception {
    if (catalog.existsTable(name))
      throw new AlreadyExistsTableException(name);

    LOG.info(path.toUri());

    TableMeta meta = TableUtil.getTableMeta(conf, path);
    TableDesc desc = new TableDescImpl(name, meta);
    desc.setPath(path);
    catalog.addTable(desc);
    LOG.info("Table " + desc.getId() + " is attached.");
  }

  @Override
  public void attachTable(String name, String strPath) throws Exception {

    if (catalog.existsTable(name))
      throw new AlreadyExistsTableException(name);

    Path path = new Path(strPath);

    LOG.info(path.toUri());

    TableMeta meta = TableUtil.getTableMeta(conf, path);
    TableDesc desc = new TableDescImpl(name, meta);
    desc.setPath(path);
    catalog.addTable(desc);
    LOG.info("Table " + desc.getId() + " is attached.");
  }

  @Override
  public void detachTable(String name) throws Exception {
    if (!catalog.existsTable(name)) {
      throw new NoSuchTableException(name);
    }

    catalog.deleteTable(name);
    LOG.info("Table " + name + " is detached.");
  }

  @Override
  public boolean existsTable(String name) {
    return catalog.existsTable(name);
  }

  public String executeQueryC(String query) throws Exception {
    catalog.updateAllTabletServingInfo(getOnlineServer());
    ResultSetOld rs = queryEngine.executeQuery(query);
    if (rs == null) {
      return "";
    } else {
      return rs.toString();
    }
  }

  public void updateQuery(String query) throws NTAQueryException {
    // TODO Auto-generated method stub

  }

  public TableDesc getTableDesc(String name) throws NoSuchTableException {
    if (!catalog.existsTable(name)) {
      throw new NoSuchTableException(name);
    }

    return catalog.getTableDesc(name);
  }

  private class ShutdownHook implements Runnable {
    @Override
    public void run() {
      shutdown();
    }
  }

  public CatalogService getCatalog() {
    return this.catalog;
  }
}