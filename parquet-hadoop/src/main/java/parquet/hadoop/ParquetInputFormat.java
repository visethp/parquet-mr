/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parquet.hadoop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

import parquet.Log;
import parquet.filter.UnboundRecordFilter;
import parquet.hadoop.api.InitContext;
import parquet.hadoop.api.ReadSupport;
import parquet.hadoop.api.ReadSupport.ReadContext;
import parquet.hadoop.metadata.BlockMetaData;
import parquet.hadoop.metadata.ColumnChunkMetaData;
import parquet.hadoop.metadata.FileMetaData;
import parquet.hadoop.metadata.GlobalMetaData;
import parquet.hadoop.metadata.ParquetMetadata;
import parquet.hadoop.util.ConfigurationUtil;
import parquet.hadoop.util.ContextUtil;
import parquet.io.ParquetDecodingException;
import parquet.schema.MessageType;
import parquet.schema.MessageTypeParser;

/**
 * The input format to read a Parquet file.
 *
 * It requires an implementation of {@link ReadSupport} to materialize the records.
 *
 * The requestedSchema will control how the original records get projected by the loader.
 * It must be a subset of the original schema. Only the columns needed to reconstruct the records with the requestedSchema will be scanned.
 *
 * @author Julien Le Dem
 *
 * @param <T> the type of the materialized records
 */
public class ParquetInputFormat<T> extends FileInputFormat<Void, T> {

  private static final Log LOG = Log.getLog(ParquetInputFormat.class);

  /**
   * key to configure the ReadSupport implementation
   */
  public static final String READ_SUPPORT_CLASS = "parquet.read.support.class";

  /**
   * key to configure the filter
   */
  public static final String UNBOUND_RECORD_FILTER = "parquet.read.filter";

  private Class<?> readSupportClass;
  private List<Footer> footers;

  public static void setReadSupportClass(Job job,  Class<?> readSupportClass) {
    ContextUtil.getConfiguration(job).set(READ_SUPPORT_CLASS, readSupportClass.getName());
  }

  public static void setUnboundRecordFilter(Job job, Class<? extends UnboundRecordFilter> filterClass) {
    ContextUtil.getConfiguration(job).set(UNBOUND_RECORD_FILTER, filterClass.getName());
  }

  public static Class<?> getUnboundRecordFilter(Configuration configuration) {
    return ConfigurationUtil.getClassFromConfig(configuration, UNBOUND_RECORD_FILTER, UnboundRecordFilter.class);
  }

  public static void setReadSupportClass(JobConf conf, Class<?> readSupportClass) {
    conf.set(READ_SUPPORT_CLASS, readSupportClass.getName());
  }

  public static Class<?> getReadSupportClass(Configuration configuration) {
    return ConfigurationUtil.getClassFromConfig(configuration, READ_SUPPORT_CLASS, ReadSupport.class);
  }

  /**
   * Hadoop will instantiate using this constructor
   */
  public ParquetInputFormat() {
  }

  /**
   * constructor used when this InputFormat in wrapped in another one (In Pig for example)
   * @param readSupportClass the class to materialize records
   */
  public <S extends ReadSupport<T>> ParquetInputFormat(Class<S> readSupportClass) {
    this.readSupportClass = readSupportClass;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RecordReader<Void, T> createRecordReader(
      InputSplit inputSplit,
      TaskAttemptContext taskAttemptContext) throws IOException, InterruptedException {
    ReadSupport<T> readSupport = getReadSupport(ContextUtil.getConfiguration(taskAttemptContext));
    Class<?> unboundRecordFilterClass = getUnboundRecordFilter(ContextUtil.getConfiguration(taskAttemptContext));
    if (unboundRecordFilterClass == null) {
      return new ParquetRecordReader<T>(readSupport);
    } else {
      try {
        return new ParquetRecordReader<T>(readSupport, (UnboundRecordFilter)unboundRecordFilterClass.newInstance());
      } catch (InstantiationException e) {
        throw new BadConfigurationException("could not instantiate unbound record filter class", e);
      } catch (IllegalAccessException e) {
        throw new BadConfigurationException("could not instantiate unbound record filter class", e);
      }
    }
  }

  /**
   * @param configuration to find the configuration for the read support
   * @return the configured read support
   */
  public ReadSupport<T> getReadSupport(Configuration configuration){
    try {
      if (readSupportClass == null) {
        readSupportClass = getReadSupportClass(configuration);
      }
      return (ReadSupport<T>)readSupportClass.newInstance();
    } catch (InstantiationException e) {
      throw new BadConfigurationException("could not instantiate read support class", e);
    } catch (IllegalAccessException e) {
      throw new BadConfigurationException("could not instantiate read support class", e);
    }
  }

  //Wrapper of hdfs blocks, keep track of which HDFS block is being used
  private static class HDFSBlocks {
    BlockLocation[] hdfsBlocks;
    int currentStartHdfsBlockIndex = 0;//the hdfs block index corresponding to the start of a row group
    int currentMidPointHDFSBlockIndex = 0;// the hdfs block index corresponding to the mid-point of a row group, a split might be created only when the midpoint of the rowgroup enters a new hdfs block

    private HDFSBlocks(BlockLocation[] hdfsBlocks) {
      this.hdfsBlocks = hdfsBlocks;
      Comparator<BlockLocation> comparator = new Comparator<BlockLocation>() {
        @Override
        public int compare(BlockLocation b1, BlockLocation b2) {
          return Long.signum(b1.getOffset() - b2.getOffset());
        }
      };
      Arrays.sort(hdfsBlocks, comparator);
    }

    private long getHDFSBlockEndingPosition(int hdfsBlockIndex) {
      BlockLocation hdfsBlock = hdfsBlocks[hdfsBlockIndex];
      return hdfsBlock.getOffset() + hdfsBlock.getLength() - 1;
    }

    /**
     * @param rowGroupMetadata
     * @return true if the mid point of row group is in a new hdfs block, and also move the currentHDFSBlock pointer to the correct index that contains the row group;
     * return false if the mid point of row group is in the same hdfs block
     */
    private boolean checkBelongingToANewHDFSBlock(BlockMetaData rowGroupMetadata) {
      boolean isNewHdfsBlock = false;
      long rowGroupMidPoint = rowGroupMetadata.getStartingPos() + (rowGroupMetadata.getCompressedSize() / 2);

      //if mid point is not in the current HDFS block any more, return true
      while (rowGroupMidPoint > getHDFSBlockEndingPosition(currentMidPointHDFSBlockIndex)) {
        isNewHdfsBlock = true;
        currentMidPointHDFSBlockIndex++;
        if (currentMidPointHDFSBlockIndex >= hdfsBlocks.length)
          throw new ParquetDecodingException("the row group is not in hdfs blocks in the file: midpoint of row groups is "
                  + rowGroupMidPoint
                  + ", the end of the hdfs block is "
                  + getHDFSBlockEndingPosition(currentMidPointHDFSBlockIndex - 1));
      }

      while (rowGroupMetadata.getStartingPos() > getHDFSBlockEndingPosition(currentStartHdfsBlockIndex)) {
        currentStartHdfsBlockIndex++;
        if (currentStartHdfsBlockIndex >= hdfsBlocks.length)
          throw new ParquetDecodingException("The row group does not start in this file: row group offset is "
                  + rowGroupMetadata.getStartingPos()
                  + " but the end of hdfs blocks of file is "
                  + getHDFSBlockEndingPosition(currentStartHdfsBlockIndex));
      }
      return isNewHdfsBlock;
    }

    public BlockLocation get(int hdfsBlockIndex) {
      return hdfsBlocks[hdfsBlockIndex];
    }

    public BlockLocation getCurrentBlock() {
      return hdfsBlocks[currentStartHdfsBlockIndex];
    }
  }

  private static class SplitInfo {
    List<BlockMetaData> rowGroups = new ArrayList<BlockMetaData>();
    BlockLocation hdfsBlock;
    long compressedByteSize = 0L;

    public SplitInfo(BlockLocation currentBlock) {
      this.hdfsBlock = currentBlock;
    }

    private void addRowGroup(BlockMetaData rowGroup) {
      this.rowGroups.add(rowGroup);
      this.compressedByteSize += rowGroup.getCompressedSize();
    }

    public long getCompressedByteSize() {
      return compressedByteSize;
    }

    public List<BlockMetaData> getRowGroups() {
      return rowGroups;
    }

    int getRowGroupCount() {
      return rowGroups.size();
    }

    public ParquetInputSplit getParquetInputSplit(FileStatus fileStatus, FileMetaData fileMetaData, String requestedSchema, Map<String, String> readSupportMetadata, String fileSchema) throws IOException {
      MessageType requested = MessageTypeParser.parseMessageType(requestedSchema);
      long length = 0;

      for (BlockMetaData block : this.getRowGroups()) {
        List<ColumnChunkMetaData> columns = block.getColumns();
        for (ColumnChunkMetaData column : columns) {
          if (requested.containsPath(column.getPath().toArray())) {
            length += column.getTotalSize();
          }
        }
      }
      return new ParquetInputSplit(
              fileStatus.getPath(),
              hdfsBlock.getOffset(),
              length,
              hdfsBlock.getHosts(),
              this.getRowGroups(),
              requestedSchema,
              fileSchema,
              fileMetaData.getKeyValueMetaData(),
              readSupportMetadata
      );
    }
  }

  /**
   * groups together all the data blocks for the same HDFS block
   *
   * @param rowGroupBlocks      data blocks (row groups)
   * @param hdfsBlocksArray     hdfs blocks
   * @param fileStatus          the containing file
   * @param fileMetaData        file level meta data
   * @param requestedSchema     the schema requested by the user
   * @param readSupportMetadata the metadata provided by the readSupport implementation in init
   * @param minSplitSize        the mapred.min.split.size
   * @param maxSplitSize        the mapred.max.split.size
   * @return the splits (one per HDFS block)
   * @throws IOException If hosts can't be retrieved for the HDFS block
   */
  static <T> List<ParquetInputSplit> generateSplits(
          List<BlockMetaData> rowGroupBlocks,
          BlockLocation[] hdfsBlocksArray,
          FileStatus fileStatus,
          FileMetaData fileMetaData,
          String requestedSchema,
          Map<String, String> readSupportMetadata, long minSplitSize, long maxSplitSize) throws IOException {
    if (maxSplitSize < minSplitSize || maxSplitSize < 0 || minSplitSize < 0) {
      throw new ParquetDecodingException("maxSplitSize and minSplitSize should be positive and max should be greater or equal to the minSplitSize: maxSplitSize = " + maxSplitSize + "; minSplitSize is " + minSplitSize);
    }
    String fileSchema = fileMetaData.getSchema().toString().intern();
    HDFSBlocks hdfsBlocks = new HDFSBlocks(hdfsBlocksArray);
    hdfsBlocks.checkBelongingToANewHDFSBlock(rowGroupBlocks.get(0));
    SplitInfo currentSplit = new SplitInfo(hdfsBlocks.getCurrentBlock());

    //assign rowGroups to splits
    List<SplitInfo> splitRowGroups = new ArrayList<SplitInfo>();
    checkSorted(rowGroupBlocks);//assert row groups are sorted
    for (BlockMetaData rowGroupMetadata : rowGroupBlocks) {
      if ((hdfsBlocks.checkBelongingToANewHDFSBlock(rowGroupMetadata)
             && currentSplit.getCompressedByteSize() >= minSplitSize
             && currentSplit.getCompressedByteSize() > 0)
           || currentSplit.getCompressedByteSize() >= maxSplitSize) {
        //create a new split
        splitRowGroups.add(currentSplit);//finish previous split
        currentSplit = new SplitInfo(hdfsBlocks.getCurrentBlock());
      }
      currentSplit.addRowGroup(rowGroupMetadata);
    }

    if (currentSplit.getRowGroupCount() > 0) {
      splitRowGroups.add(currentSplit);
    }

    //generate splits from rowGroups of each split
    List<ParquetInputSplit> resultSplits = new ArrayList<ParquetInputSplit>();
    for (SplitInfo splitInfo : splitRowGroups) {
      ParquetInputSplit split = splitInfo.getParquetInputSplit(fileStatus, fileMetaData, requestedSchema, readSupportMetadata, fileSchema);
      resultSplits.add(split);
    }
    return resultSplits;
  }

  private static void checkSorted(List<BlockMetaData> rowGroupBlocks) {
    long previousOffset = 0L;
    for(BlockMetaData rowGroup: rowGroupBlocks) {
      long currentOffset = rowGroup.getStartingPos();
      if (currentOffset < previousOffset) {
        throw new ParquetDecodingException("row groups are not sorted: previous row groups starts at " + previousOffset + ", current row group starts at " + currentOffset);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<InputSplit> getSplits(JobContext jobContext) throws IOException {
    List<InputSplit> splits = new ArrayList<InputSplit>();
    splits.addAll(getSplits(ContextUtil.getConfiguration(jobContext), getFooters(jobContext)));
    return splits;
  }

  /**
   * @param configuration the configuration to connect to the file system
   * @param footers the footers of the files to read
   * @return the splits for the footers
   * @throws IOException
   */
  public List<ParquetInputSplit> getSplits(Configuration configuration, List<Footer> footers) throws IOException {
    final long maxSplitSize = configuration.getLong("mapred.max.split.size", Long.MAX_VALUE);
    final long minSplitSize = Math.max(getFormatMinSplitSize(), configuration.getLong("mapred.min.split.size", 0L));
    if (maxSplitSize < 0 || minSplitSize < 0) {
      throw new ParquetDecodingException("maxSplitSize or minSplitSie should not be negative: maxSplitSize = " + maxSplitSize + "; minSplitSize = " + minSplitSize);
    }
    List<ParquetInputSplit> splits = new ArrayList<ParquetInputSplit>();
    GlobalMetaData globalMetaData = ParquetFileWriter.getGlobalMetaData(footers);
    ReadContext readContext = getReadSupport(configuration).init(new InitContext(
        configuration,
        globalMetaData.getKeyValueMetaData(),
        globalMetaData.getSchema()));
    for (Footer footer : footers) {
      final Path file = footer.getFile();
      LOG.debug(file);
      FileSystem fs = file.getFileSystem(configuration);
      FileStatus fileStatus = fs.getFileStatus(file);
      ParquetMetadata parquetMetaData = footer.getParquetMetadata();
      List<BlockMetaData> blocks = parquetMetaData.getBlocks();
      BlockLocation[] fileBlockLocations = fs.getFileBlockLocations(fileStatus, 0, fileStatus.getLen());
      splits.addAll(
          generateSplits(
              blocks,
              fileBlockLocations,
              fileStatus,
              parquetMetaData.getFileMetaData(),
              readContext.getRequestedSchema().toString(),
              readContext.getReadSupportMetadata(),
              minSplitSize,
              maxSplitSize)
          );
    }
    return splits;
  }

  /*
   * This is to support multi-level/recursive directory listing until
   * MAPREDUCE-1577 is fixed.
   */
  @Override
  protected List<FileStatus> listStatus(JobContext jobContext) throws IOException {
    return getAllFileRecursively(super.listStatus(jobContext),
       ContextUtil.getConfiguration(jobContext));
  }

  private static List<FileStatus> getAllFileRecursively(
      List<FileStatus> files, Configuration conf) throws IOException {
    List<FileStatus> result = new ArrayList<FileStatus>();
    int len = files.size();
    for (int i = 0; i < len; ++i) {
      FileStatus file = files.get(i);
      if (file.isDir()) {
        Path p = file.getPath();
        FileSystem fs = p.getFileSystem(conf);
        staticAddInputPathRecursively(result, fs, p, hiddenFileFilter);
      } else {
        result.add(file);
      }
    }
    LOG.info("Total input paths to process : " + result.size());
    return result;
  }

  private static void staticAddInputPathRecursively(List<FileStatus> result,
      FileSystem fs, Path path, PathFilter inputFilter)
          throws IOException {
    for (FileStatus stat: fs.listStatus(path, inputFilter)) {
      if (stat.isDir()) {
        staticAddInputPathRecursively(result, fs, stat.getPath(), inputFilter);
      } else {
        result.add(stat);
      }
    }
  }

  private static final PathFilter hiddenFileFilter = new PathFilter(){
    public boolean accept(Path p){
      String name = p.getName();
      return !name.startsWith("_") && !name.startsWith(".");
    }
  };

  /**
   * @param jobContext the current job context
   * @return the footers for the files
   * @throws IOException
   */
  public List<Footer> getFooters(JobContext jobContext) throws IOException {
    if (footers == null) {
      footers = getFooters(ContextUtil.getConfiguration(jobContext), listStatus(jobContext));
    }

    return footers;
  }

  /**
   * the footers for the files
   * @param configuration to connect to the file system
   * @param statuses the files to open
   * @return the footers of the files
   * @throws IOException
   */
  public List<Footer> getFooters(Configuration configuration, List<FileStatus> statuses) throws IOException {
    LOG.debug("reading " + statuses.size() + " files");
    return ParquetFileReader.readAllFootersInParallelUsingSummaryFiles(configuration, statuses);
  }

  /**
   * @param jobContext the current job context
   * @return the merged metadata from the footers
   * @throws IOException
   */
  public GlobalMetaData getGlobalMetaData(JobContext jobContext) throws IOException {
    return ParquetFileWriter.getGlobalMetaData(getFooters(jobContext));
  }

}
