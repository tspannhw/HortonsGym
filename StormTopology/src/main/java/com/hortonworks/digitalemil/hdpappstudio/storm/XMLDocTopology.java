package com.hortonworks.digitalemil.hdpappstudio.storm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import storm.kafka.BrokerHosts;
import storm.kafka.KafkaSpout;
import storm.kafka.SpoutConfig;
import storm.kafka.ZkHosts;
import storm.kafka.bolt.KafkaBolt;
import storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import storm.kafka.bolt.selector.DefaultTopicSelector;
import storm.kafka.trident.TridentKafkaState;

import org.apache.storm.hbase.bolt.HBaseBolt;
import org.apache.storm.hbase.bolt.mapper.SimpleHBaseMapper;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;

import org.apache.storm.hdfs.bolt.HdfsBolt;
import org.apache.storm.hdfs.bolt.format.DefaultFileNameFormat;
import org.apache.storm.hdfs.bolt.format.DelimitedRecordFormat;
import org.apache.storm.hdfs.bolt.format.FileNameFormat;
import org.apache.storm.hdfs.bolt.format.RecordFormat;
import org.apache.storm.hdfs.bolt.rotation.*;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy.Units;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.bolt.sync.SyncPolicy;

public class XMLDocTopology {

	public static final String KAFKA_SPOUT_ID = "kafka-spout";
	public static final String INDEXSOLR_BOLT_ID = "solr-bolt";
	public static final String RAWHDFS_BOLT_ID = "raw-hdfs-bolt";
	
	public static final String TUPLETRANSFORMER_BOLT_ID = "tupletransformer-bolt";
	
	public static void main(String[] args) throws Exception {
		
		if(args.length< 9) {
			System.err.println("Error Deploying Topology. Too few arguments. I needed:\n0. Topology Name\n1. ZooKeeper Hosts  e.g. 127.0.0.1\n2. Solr URL e.g. http://127.0.0.1:8983/solr/locations/update/json?commit=true\n3. HBaseTablename\n4. HBaseColumnFamily\n5. Kafka Topic\n6. HiveTable\n.7. HBaseRootdir\n8. Zookeeper.Znode.Parent\nfield0\n...fieldN");
		}
		String tname= args[0];
		BrokerHosts hosts = new ZkHosts(args[1]);
		
		String kafkaTopic= args[5];
		System.out.println("Kafka Topic: "+kafkaTopic);
		
		SpoutConfig spoutConfig = new SpoutConfig(hosts, kafkaTopic, "/kafkastorm","src");
		spoutConfig.useStartOffsetTimeIfOffsetOutOfRange= true;
		spoutConfig.startOffsetTime= System.currentTimeMillis();
	
		KafkaSpout kafkaSpout = new KafkaSpout(spoutConfig);
		//IndexSolr index= new IndexSolr(args[2]);
		XMLDocSolrIndexer index= new XMLDocSolrIndexer(args[2]);
		
		
		int l= args.length- 9;
		String [] keys= new String[l];
		
		for(int i=0; i< l; i++) {
			keys[i]= args[i+9];
			System.out.print("Field: "+keys[i]+" ");
		}
		System.out.println();
			
		List<String> fields = new ArrayList<String>();
		for (int i = 0; i < keys.length; i++) {
			fields.add(keys[i]);
		}
	
		String hbasetable= args[3];
		String columnfamily= args[4];
		String hivetable= args[6];
		String hbaserootdir= args[7];
		String zookeeperznodeparent= args[8];
		
		System.out.println("HBase Table: "+hbasetable);
		System.out.println("HBase CF: "+columnfamily);
		System.out.println("Hive Table: "+hivetable);
		
		Config stormconfig = new Config();

        Map<String, Object> hbConf = new HashMap<String, Object>();
        hbConf.put("hbase.rootdir", hbaserootdir);
        hbConf.put("zookeeper.znode.parent", zookeeperznodeparent);
        stormconfig.put("hbase.conf", hbConf);
        
        Properties props = new Properties();
        props.put("metadata.broker.list", "sandbox:6667");
        props.put("request.required.acks", "1");
        props.put("serializer.class", "kafka.serializer.StringEncoder");
        stormconfig.put(TridentKafkaState.KAFKA_BROKER_PROPERTIES, props);
		
		
		SimpleHBaseMapper mapper = new SimpleHBaseMapper() 
        .withRowKeyField(keys[0])
        .withColumnFields(new Fields(fields))
        .withColumnFamily(columnfamily);
		
		String ttkey[]= {"xml"};
		IdentityTransformer tt= new IdentityTransformer(ttkey);
		
		RecordFormat format = new DelimitedRecordFormat().withFields(new Fields(fields)).withFieldDelimiter("|");

		//Synchronize data buffer with the filesystem every 1000 tuples
		SyncPolicy syncPolicy = new CountSyncPolicy(1);

		// Rotate data files when they reach five MB
		FileRotationPolicy rotationPolicy = new FileSizeRotationPolicy(5.0f, Units.MB);

		// Use default, Storm-generated file names
		FileNameFormat fileNameFormat = new DefaultFileNameFormat().withPath("/user/guest/hdpdemostudio/hive/"+hivetable);

		format = new com.hortonworks.digitalemil.hdpappstudio.storm.RecordFormat();
		fileNameFormat = new DefaultFileNameFormat().withPath("/user/guest/hdpdemostudio/raw/"+hivetable);
		HdfsBolt rawBolt=
				new HdfsBolt()
        .withFsUrl("hdfs://sandbox.hortonworks.com:8020")
        .withFileNameFormat(fileNameFormat)
        .withRecordFormat(format)
        .withRotationPolicy(rotationPolicy)
        .withSyncPolicy(syncPolicy);
		
    
		TopologyBuilder builder = new TopologyBuilder();		
					
		builder.setSpout(KAFKA_SPOUT_ID, kafkaSpout);
			
		builder.setBolt(TUPLETRANSFORMER_BOLT_ID, tt).shuffleGrouping(KAFKA_SPOUT_ID);
		builder.setBolt(RAWHDFS_BOLT_ID, rawBolt).shuffleGrouping(KAFKA_SPOUT_ID);
		builder.setBolt(INDEXSOLR_BOLT_ID, index).shuffleGrouping(TUPLETRANSFORMER_BOLT_ID);
		
		//LocalCluster cluster = new LocalCluster();
		//cluster.submitTopology(TOPOLOGY_NAME, config, builder.createTopology());
		//Utils.waitForSeconds(1000);
		//cluster.killTopology(TOPOLOGY_NAME);
		//cluster.shutdown();
		
		StormSubmitter.submitTopology(tname, stormconfig, builder.createTopology());
	}
}

