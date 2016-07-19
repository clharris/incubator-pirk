/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.pirk.responder.wideskies.storm;

import org.apache.log4j.Logger;
import org.apache.pirk.query.wideskies.QueryInfo;
import org.apache.pirk.responder.wideskies.common.HashSelectorAndPartitionData;
import org.apache.pirk.utils.LogUtils;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import scala.Tuple2;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;

public class HashBolt extends BaseBasicBolt
{
  private QueryInfo queryInfo;

  private static Logger logger = LogUtils.getLoggerForThisClass();

  private JSONParser parser;
  private JSONObject json = new JSONObject();
  private Tuple2<Integer,ArrayList<BigInteger>> hashPartitionPairs;

  private boolean splitPartitions;

  @Override public void prepare(Map map, TopologyContext context)
  {
    splitPartitions = (boolean) map.get(StormConstants.SPLIT_PARTITIONS_KEY);
    try
    {
      StormUtils.initializeSchemas(map);
    } catch (Exception e)
    {
      logger.error("Unable to initialize schemas in HashBolt. ", e);
    }
    queryInfo = new QueryInfo((Map) map.get(StormConstants.QUERY_INFO_KEY));
    parser = new JSONParser();

    logger.info("Initialized HashBolt.");
  }

  @Override public void execute(Tuple tuple, BasicOutputCollector outputCollector)
  {

    // Receives JSON record from KafkaSpout. Outputs hash of the selector along with partitioned ArrayList of partitioned data.

    String record = tuple.getString(0);

    try
    {
      json = (JSONObject) parser.parse(record);
    } catch (ParseException e)
    {
      logger.warn("Unable to parse record.\n" + record);
    }

    /***
     * Two options here: output all partitioned data from the record as a single array, or output each partition
     * element individually.  This is configurable via the splitPartitions parameter.  The latter approach seems
     * to give better throughput, but still some uncertainty about possible ordering issues
     */
    try
    {
      hashPartitionPairs = HashSelectorAndPartitionData.hashSelectorAndFormPartitions(json, queryInfo);
      logger.debug("Hashbolt processing " + json.toString() + " outputting results - " + hashPartitionPairs._2().size());

      if (splitPartitions)
      {
        for (BigInteger bigInt : hashPartitionPairs._2())
        {
          outputCollector.emit(new Values(hashPartitionPairs._1(), bigInt));
        }
      }
      else
      {
        outputCollector.emit(new Values(hashPartitionPairs._1(), hashPartitionPairs._2()));
      }

    } catch (Exception e)
    {
      logger.warn("Failed to partition data for record -- " + json + "\n", e);
    }
  }

  @Override public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer)
  {
    outputFieldsDeclarer.declare(new Fields(StormConstants.HASH_FIELD, StormConstants.PARTIONED_DATA_FIELD));
  }
}
