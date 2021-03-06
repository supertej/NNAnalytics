/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hadoop.hdfs.server.namenode.analytics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.qjournal.MiniQJMHACluster;
import org.apache.hadoop.hdfs.server.namenode.SRandom;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public abstract class TestWithMiniClusterBase {

  protected static final SRandom RANDOM = new SRandom();
  protected static final int NUMDATANODES = 1;
  protected static final Configuration CONF = new HdfsConfiguration();
  protected static final String NAMESERVICE = MiniQJMHACluster.NAMESERVICE;

  protected static final byte[] TINY_FILE_BYTES = new byte[512];
  protected static final byte[] SMALL_FILE_BYTES = new byte[1024];
  protected static final byte[] MEDIUM_FILE_BYTES = new byte[1024 * 1024];
  protected static final String[] USERS = new String[] {"hdfs", "test_user"};

  protected static MiniQJMHACluster cluster;
  protected static HttpHost hostPort;
  protected static HttpClient client;
  protected static ApplicationMain nna;

  @AfterClass
  public static void tearDown() throws IOException {
    if (nna != null) {
      nna.shutdown();
    }
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Before
  public void before() throws IOException {
    client = new DefaultHttpClient();

    boolean isServingQueries = false;
    while (!isServingQueries) {
      try {
        HttpGet get = new HttpGet("http://localhost:4567/info");
        HttpResponse res = client.execute(hostPort, get);
        String body = IOUtils.toString(res.getEntity().getContent());
        if (body.contains("Ready to service queries: true")) {
          isServingQueries = true;
        } else {
          Thread.sleep(1000L);
        }
      } catch (Exception ex) {
        // Do nothing.
      }
    }
  }

  @Test
  public void testLoadingStatus() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/loadingStatus");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Test
  public void testConf() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/config");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Test
  public void testHistory() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/history");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Test
  public void testDrop1() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/drop?table=LOGIN");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Test
  public void testDrop2() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/drop?table=HISTORY");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Test
  public void testTruncate1() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/truncate?table=HISTORY&limit=1");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Ignore("Seems to break")
  @Test
  public void testTruncate2() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/truncate?table=LOGIN&limit=1");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Test
  public void testTokens() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/token");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
  }

  @Test(timeout = 60000L)
  public void testSaveNamespace() throws Exception {
    String namespaceDir = MiniDFSCluster.getBaseDirectory() + "/dfs/name/current/";
    FileUtils.forceMkdir(new File(namespaceDir));
    HttpGet get = new HttpGet("http://localhost:4567/saveNamespace");
    executeSaveNamespace(get);
  }

  @Test(timeout = 60000L)
  public void testSaveLegacyNamespace() throws Exception {
    String legacyBaseDir = MiniDFSCluster.getBaseDirectory() + "/dfs/name/legacy/";
    FileUtils.forceMkdir(new File(legacyBaseDir));
    HttpGet get =
        new HttpGet("http://localhost:4567/saveNamespace?legacy=true&dir=" + legacyBaseDir);
    executeSaveNamespace(get);
  }

  private void executeSaveNamespace(HttpGet get) throws IOException {
    boolean testingDone = false;
    do {
      HttpResponse res = client.execute(hostPort, get);
      assertThat(res.getStatusLine().getStatusCode(), is(200));
      String body = IOUtils.toString(res.getEntity().getContent());
      if (body.contains("Already")) {
        continue;
      }
      if (body.contains("failed")) {
        assertThat(body, containsString("UnsupportedOperation"));
        testingDone = true;
      } else {
        assertThat(body, containsString("Done."));
        testingDone = true;
      }
    } while (!testingDone);
  }

  @Test
  public void testTokenExtractor() throws IOException {
    Map<String, Long> tokenLastLogins = nna.getLoader().getTokenExtractor().getTokenLastLogins();
    assertThat(tokenLastLogins.size(), is(0));
  }

  @Test(timeout = 120000L)
  public void testUpdateSeen() throws Exception {
    HttpGet get = new HttpGet("http://localhost:4567/filter?set=files&sum=count");
    HttpResponse res = client.execute(hostPort, get);
    assertThat(res.getStatusLine().getStatusCode(), is(200));
    String content = IOUtils.toString(res.getEntity().getContent());
    int startingCount = Integer.parseInt(content);

    // Trigger file system updates.
    addFiles(100, 0L);

    // Ensure NNA sees those updates in query.
    int checkCount;
    do {
      HttpGet check = new HttpGet("http://localhost:4567/filter?set=dirs&sum=count");
      HttpResponse checkRes = client.execute(hostPort, check);
      assertThat(checkRes.getStatusLine().getStatusCode(), is(200));
      String checkContent = IOUtils.toString(checkRes.getEntity().getContent());
      checkCount = Integer.parseInt(checkContent);
      Thread.sleep(200L);
    } while (checkCount == startingCount);

    assertThat(checkCount, is(greaterThan(startingCount)));

    // Test NsQuota histogram is not empty.
    HttpGet histogram =
        new HttpGet(
            "http://localhost:4567/histogram?set=files&filters=isUnderNsQuota:eq:true&parentDirDepth=3&sum=count&type=parentDir&histogramOutput=csv");
    HttpResponse checkRes = client.execute(hostPort, histogram);
    assertThat(checkRes.getStatusLine().getStatusCode(), is(200));
    List<String> checkContent = IOUtils.readLines(checkRes.getEntity().getContent());
    assertThat(checkContent.size(), is(greaterThan(0)));
  }

  protected void addFiles(int numOfFiles, long sleepBetweenMs) throws Exception {
    DistributedFileSystem fileSystem = (DistributedFileSystem) FileSystem.get(CONF);
    for (int i = 0; i < numOfFiles; i++) {
      int dirNumber1 = RANDOM.nextInt(10);
      Path dirPath = new Path("/dir" + dirNumber1);
      int dirNumber2 = RANDOM.nextInt(10);
      dirPath = dirPath.suffix("/dir" + dirNumber2);
      int dirNumber3 = RANDOM.nextInt(10);
      dirPath = dirPath.suffix("/dir" + dirNumber3);
      fileSystem.mkdirs(dirPath);
      Path filePath = dirPath.suffix("/file" + i);
      int fileType = RANDOM.nextInt(5);
      switch (fileType) {
        case 0:
          filePath = filePath.suffix(".zip");
          break;
        case 1:
          filePath = filePath.suffix(".avro");
          break;
        case 2:
          filePath = filePath.suffix(".orc");
          break;
        case 3:
          filePath = filePath.suffix(".txt");
          break;
        case 4:
          filePath = filePath.suffix(".json");
          break;
        default:
          break;
      }
      int fileSize = RANDOM.nextInt(4);
      switch (fileSize) {
        case 0:
          DFSTestUtil.writeFile(fileSystem, filePath, "");
          break;
        case 1:
          DFSTestUtil.writeFile(fileSystem, filePath, new String(TINY_FILE_BYTES));
          break;
        case 2:
          DFSTestUtil.writeFile(fileSystem, filePath, new String(SMALL_FILE_BYTES));
          break;
        case 3:
          DFSTestUtil.writeFile(fileSystem, filePath, new String(MEDIUM_FILE_BYTES));
          break;
        default:
          DFSTestUtil.writeFile(fileSystem, filePath, "");
          break;
      }
      if (dirNumber1 == 1) {
        fileSystem.setQuota(filePath.getParent(), 9999999L, 100000000000000L);
      }
      int user = RANDOM.nextInt(3);
      switch (user) {
        case 0:
          break;
        case 1:
          fileSystem.setOwner(filePath, USERS[0], USERS[0]);
          break;
        case 2:
          fileSystem.setOwner(filePath, USERS[1], USERS[1]);
          break;
        default:
          break;
      }
      short repFactor = (short) RANDOM.nextInt(4);
      if (repFactor != 0) {
        fileSystem.setReplication(filePath, repFactor);
      }
      int weeksAgo = RANDOM.nextInt(60);
      long timeStamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(weeksAgo * 7);
      if (weeksAgo != 0) {
        fileSystem.setTimes(filePath, timeStamp, timeStamp);
      }
      if (sleepBetweenMs != 0L) {
        Thread.sleep(sleepBetweenMs);
      }
    }
  }
}
