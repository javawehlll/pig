/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pig.backend.hadoop.executionengine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketImplFactory;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobSubmissionProtocol;
import org.apache.hadoop.mapred.JobTracker;
import org.apache.pig.backend.datastorage.DataStorage;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.executionengine.ExecJob;
import org.apache.pig.backend.executionengine.ExecPhysicalOperator;
import org.apache.pig.backend.executionengine.ExecutionEngine;
import org.apache.pig.backend.hadoop.datastorage.HConfiguration;
import org.apache.pig.backend.hadoop.datastorage.HDataStorage;
import org.apache.pig.builtin.BinStorage;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.FileLocalizer;
import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.logicalLayer.LogicalPlan;
import org.apache.pig.impl.logicalLayer.LogToPhyTranslationVisitor;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.mapReduceLayer.MapReduceLauncher;
import org.apache.pig.impl.physicalLayer.PhysicalOperator;
import org.apache.pig.impl.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.impl.physicalLayer.relationalOperators.POStore;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.shock.SSHSocketImplFactory;


public class HExecutionEngine implements ExecutionEngine {
    
    private final Log log = LogFactory.getLog(getClass());
    
    protected PigContext pigContext;
    
    protected DataStorage ds;
    protected HConfiguration conf;
    
    protected JobSubmissionProtocol jobTracker;
    protected JobClient jobClient;

    // key: the operator key from the logical plan that originated the physical plan
    // val: the operator key for the root of the phyisical plan
    protected Map<OperatorKey, OperatorKey> logicalToPhysicalKeys;
    
    protected Map<OperatorKey, ExecPhysicalOperator> physicalOpTable;
    
    // map from LOGICAL key to into about the execution
    protected Map<OperatorKey, MapRedResult> materializedResults;
    
    public HExecutionEngine(PigContext pigContext,
                            HConfiguration conf) {
        this.pigContext = pigContext;
        this.conf = conf;
        this.logicalToPhysicalKeys = new HashMap<OperatorKey, OperatorKey>();
        this.physicalOpTable = new HashMap<OperatorKey, ExecPhysicalOperator>();
        this.materializedResults = new HashMap<OperatorKey, MapRedResult>();
        
        this.ds = null;
        
        // to be set in the init method
        this.jobTracker = null;
        this.jobClient = null;
    }
    
    public JobClient getJobClient() {
        return this.jobClient;
    }
    
    public Map<OperatorKey, MapRedResult> getMaterializedResults() {
        return this.materializedResults;
    }
    
    public HExecutionEngine(PigContext pigContext) {
        this(pigContext, new HConfiguration(new JobConf()));
    }
                            
    public Map<OperatorKey, ExecPhysicalOperator> getPhysicalOpTable() {
        return this.physicalOpTable;
    }
    
    
    public DataStorage getDataStorage() {
        return this.ds;
    }
    
    private void setJobtrackerLocation(String newLocation) {
        conf.put("mapred.job.tracker", newLocation);
    }

    private void setFilesystemLocation(String newLocation) {
        conf.put("fs.default.name", newLocation);
    }

    public void init() throws ExecException {
        //First set the ssh socket factory
        setSSHFactory();
        
        String hodServer = System.getProperty("hod.server");
    
        if (hodServer != null && hodServer.length() > 0) {
            String hdfsAndMapred[] = doHod(hodServer);
            setFilesystemLocation(hdfsAndMapred[0]);
            setJobtrackerLocation(hdfsAndMapred[1]);
        }
        else {
            String cluster = System.getProperty("cluster");
            if (cluster != null && cluster.length() > 0) {
                if(cluster.indexOf(':') < 0) {
                    cluster = cluster + ":50020";
                }
                setJobtrackerLocation(cluster);
            }

            String nameNode = System.getProperty("namenode");
            if (nameNode!=null && nameNode.length() > 0) {
                if(nameNode.indexOf(':') < 0) {
                    nameNode = nameNode + ":8020";
                }
                setFilesystemLocation(nameNode);
            }
        }
     
        log.info("Connecting to hadoop file system at: " + conf.get("fs.default.name"));

        try {
            ds = new HDataStorage(conf);
        }
        catch (IOException e) {
            throw new ExecException("Failed to create DataStorage", e);
        }
        
        String jobTrackerName = conf.get("mapred.job.tracker").toString();
        log.info("Connecting to map-reduce job tracker at: " + jobTrackerName);
        
        try {
            if(!jobTrackerName.equalsIgnoreCase("local"))
                jobTracker = (JobSubmissionProtocol) RPC.getProxy(JobSubmissionProtocol.class,
                                                              JobSubmissionProtocol.versionID, 
                                                              JobTracker.getAddress(conf.getConfiguration()),
                                                              conf.getConfiguration());
        }
        catch (IOException e) {
            throw new ExecException("Failed to crate job tracker", e);
        }

        try {
            jobClient = new JobClient(new JobConf(conf.getConfiguration()));
        }
        catch (IOException e) {
            throw new ExecException("Failed to create job client", e);
        }
    }

    public void close() throws ExecException {
        ;
    }
        
    public Properties getConfiguration() throws ExecException {
        return this.conf;
    }
        
    public void updateConfiguration(Properties newConfiguration) 
            throws ExecException {
        Enumeration keys = newConfiguration.propertyNames();
        
        while (keys.hasMoreElements()) {
            Object obj = keys.nextElement();
            
            if (obj instanceof String) {
                String str = (String) obj;
                
                conf.put(str, newConfiguration.get(str));
            }
        }
    }
        
    public Map<String, Object> getStatistics() throws ExecException {
        throw new UnsupportedOperationException();
    }

    public PhysicalPlan compile(LogicalPlan plan,
                                Properties properties) throws ExecException {
        if (plan == null) {
            throw new ExecException("No Plan to compile");
        }

        try {
            LogToPhyTranslationVisitor translator = 
                new LogToPhyTranslationVisitor(plan);
            translator.setPigContext(pigContext);
            translator.visit();
            return translator.getPhysicalPlan();
        } catch (VisitorException ve) {
            throw new ExecException(ve);
        }
    }

    public ExecJob execute(PhysicalPlan plan,
                           String jobName) throws ExecException {
        try {
            PhysicalOperator leaf = (PhysicalOperator)plan.getLeaves().get(0);
            FileSpec spec = null;
            if(!(leaf instanceof POStore)){
                String scope = leaf.getOperatorKey().getScope();
                POStore str = new POStore(new OperatorKey(scope,
                    NodeIdGenerator.getGenerator().getNextNodeId(scope)));
                str.setPc(pigContext);
                spec = new FileSpec(FileLocalizer.getTemporaryPath(null,
                    pigContext).toString(),
                    BinStorage.class.getName());
                str.setSFile(spec);
                plan.addAsLeaf(str);
            }
            else{
                spec = ((POStore)leaf).getSFile();
            }

            MapReduceLauncher launcher = new MapReduceLauncher();
            launcher.launchPig(plan, jobName, pigContext);
            return new HJob(ExecJob.JOB_STATUS.COMPLETED, pigContext, spec);

        } catch (Exception e) {
            // There are a lot of exceptions thrown by the launcher.  If this
            // is an ExecException, just let it through.  Else wrap it.
            if (e instanceof ExecException) throw (ExecException)e;
            else throw new ExecException(e.getMessage(), e);
        }

    }

    public ExecJob submit(PhysicalPlan plan,
                          String jobName) throws ExecException {
        throw new UnsupportedOperationException();
    }

    public void explain(PhysicalPlan plan, PrintStream stream) {
        // TODO FIX
    }

    public Collection<ExecJob> runningJobs(Properties properties) throws ExecException {
        throw new UnsupportedOperationException();
    }
    
    public Collection<String> activeScopes() throws ExecException {
        throw new UnsupportedOperationException();
    }
    
    public void reclaimScope(String scope) throws ExecException {
        throw new UnsupportedOperationException();
    }
    
    private void setSSHFactory(){
        String g = System.getProperty("ssh.gateway");
        if (g == null || g.length() == 0) return;
        try {
            Class clazz = Class.forName("org.apache.pig.shock.SSHSocketImplFactory");
            SocketImplFactory f = (SocketImplFactory)clazz.getMethod("getFactory", new Class[0]).invoke(0, new Object[0]);
            Socket.setSocketImplFactory(f);
        } 
        catch (SocketException e) {}
        catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    //To prevent doing hod if the pig server is constructed multiple times
    private static String hodMapRed;
    private static String hodHDFS;

    private enum ParsingState {
        NOTHING, HDFSUI, MAPREDUI, HDFS, MAPRED, HADOOPCONF
    };
    
    private String[] doHod(String server) throws ExecException {
        if (hodMapRed != null) {
            return new String[] {hodHDFS, hodMapRed};
        }
        
        try {
            Process p = null;
            // Make the kryptonite released version the default if nothing
            // else is specified.
            StringBuilder cmd = new StringBuilder();
            cmd.append(System.getProperty("hod.expect.root"));
            cmd.append('/');
            cmd.append("libexec/pig/");
            cmd.append(System.getProperty("hod.expect.uselatest"));
            cmd.append('/');
            cmd.append(System.getProperty("hod.command"));

            String cluster = System.getProperty("yinst.cluster");
           
            // TODO This is a Yahoo specific holdover, need to remove
            // this.
            if (cluster != null && cluster.length() > 0 && !cluster.startsWith("kryptonite")) {
                cmd.append(" --config=");
                cmd.append(System.getProperty("hod.config.dir"));
                cmd.append('/');
                cmd.append(cluster);
            }

            cmd.append(" " + System.getProperty("hod.param", ""));

            if (server.equals("local")) {
                p = Runtime.getRuntime().exec(cmd.toString());
            } 
            else {
                SSHSocketImplFactory fac = SSHSocketImplFactory.getFactory(server);
                p = fac.ssh(cmd.toString());
            }
            
            InputStream is = p.getInputStream();

            log.info("Connecting to HOD...");
            log.debug("sending HOD command " + cmd.toString());

            StringBuffer sb = new StringBuffer();
            int c;
            String hdfsUI = null;
            String mapredUI = null;
            String hdfs = null;
            String mapred = null;
            String hadoopConf = null;

            ParsingState current = ParsingState.NOTHING;

            while((c = is.read()) != -1 && mapred == null) {
                if (c == '\n' || c == '\r') {
                    switch(current) {
                    case HDFSUI:
                        hdfsUI = sb.toString().trim();
                        log.info("HDFS Web UI: " + hdfsUI);
                        break;
                    case HDFS:
                        hdfs = sb.toString().trim();
                        log.info("HDFS: " + hdfs);
                        break;
                    case MAPREDUI:
                        mapredUI = sb.toString().trim();
                        log.info("JobTracker Web UI: " + mapredUI);
                        break;
                    case MAPRED:
                        mapred = sb.toString().trim();
                        log.info("JobTracker: " + mapred);
                        break;
                    case HADOOPCONF:
                        hadoopConf = sb.toString().trim();
                        log.info("HadoopConf: " + hadoopConf);
                        break;
                    }
                    current = ParsingState.NOTHING;
                    sb = new StringBuffer();
                }
                sb.append((char)c);
                if (sb.indexOf("hdfsUI:") != -1) {
                    current = ParsingState.HDFSUI;
                    sb = new StringBuffer();
                } 
                else if (sb.indexOf("hdfs:") != -1) {
                    current = ParsingState.HDFS;
                    sb = new StringBuffer();
                } 
                else if (sb.indexOf("mapredUI:") != -1) {
                    current = ParsingState.MAPREDUI;
                    sb = new StringBuffer();
                } 
                else if (sb.indexOf("mapred:") != -1) {
                    current = ParsingState.MAPRED;
                    sb = new StringBuffer();
                } 
                else if (sb.indexOf("hadoopConf:") != -1) {
                    current = ParsingState.HADOOPCONF;
                    sb = new StringBuffer();
                }    
            }
            
            hdfsUI = fixUpDomain(hdfsUI);
            hdfs = fixUpDomain(hdfs);
            mapredUI = fixUpDomain(mapredUI);
            mapred = fixUpDomain(mapred);
            hodHDFS = hdfs;
            hodMapRed = mapred;

            if (hadoopConf != null) {
                JobConf jobConf = new JobConf(hadoopConf);
                jobConf.addResource("pig-cluster-hadoop-site.xml");
                
                conf = new HConfiguration(jobConf);
                
                // make sure that files on class path are used
                System.out.println("Job Conf = " + conf);
                System.out.println("dfs.block.size= " + conf.get("dfs.block.size"));
                System.out.println("ipc.client.timeout= " + conf.get("ipc.client.timeout"));
                System.out.println("mapred.child.java.opts= " + conf.get("mapred.child.java.opts"));
            }
            else {
                throw new IOException("Missing Hadoop configuration file");
            }
            return new String[] {hdfs, mapred};
        } 
        catch (Exception e) {
            ExecException ee = new ExecException("Could not connect to HOD");
            ee.initCause(e);
            throw ee;
        }
    }

    private String fixUpDomain(String hostPort) throws UnknownHostException {
        String parts[] = hostPort.split(":");
        if (parts[0].indexOf('.') == -1) {
            parts[0] = parts[0] + ".inktomisearch.com";
        }
        InetAddress.getByName(parts[0]);
        return parts[0] + ":" + parts[1];
    }
    
}




