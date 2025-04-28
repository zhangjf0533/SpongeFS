/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.hadoop.mapreduce.lib.input;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;

public final class LineRecordReaderHelper extends
    BaseLineRecordReaderHelper {

  public LineRecordReaderHelper(Path filePath, Configuration conf) {
    super(filePath, conf);
  }

  @Override
  public long countRecords(long start, long length) throws IOException {
    try (LineRecordReader reader = newReader(start, length)) {
      long numRecords = 0L;
      while (reader.nextKeyValue()) {
        numRecords++;
      }
      return numRecords;
    }
  }

  private LineRecordReader newReader(long start, long length)
      throws IOException {
    FileSplit split = new FileSplit(getFilePath(), start, length, null);

    TaskAttemptContext context =
        new TaskAttemptContextImpl(getConf(), new TaskAttemptID());

    LineRecordReader reader = new LineRecordReader(getRecordDelimiterBytes());
    try {
      reader.initialize(split, context);
      return reader;
    } catch (Throwable e) {
      reader.close();
      throw e;
    }
  }
}
