package edu.berkeley.cs.succinct.streams;

import edu.berkeley.cs.succinct.SuccinctIndexedFile;
import edu.berkeley.cs.succinct.regex.parser.RegExParsingException;
import edu.berkeley.cs.succinct.util.Range;
import edu.berkeley.cs.succinct.util.SearchIterator;
import edu.berkeley.cs.succinct.util.SearchRecordIterator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.util.*;

public class SuccinctIndexedFileStream extends SuccinctFileStream implements SuccinctIndexedFile {

  protected transient int[] offsets;
  protected transient long endOfIndexedFileStream;

  /**
   * Constructor to map a file containing Succinct data structures via streams.
   *
   * @param filePath Path of the file.
   * @param conf     Configuration for the filesystem.
   * @throws IOException
   */
  public SuccinctIndexedFileStream(Path filePath, Configuration conf) throws IOException {
    super(filePath, conf);
    FSDataInputStream is = getStream(filePath);
    is.seek(endOfFileStream);
    int len = is.readInt();
    offsets = new int[len];
    for (int i = 0; i < len; i++) {
      offsets[i] = is.readInt();
    }
    endOfIndexedFileStream = is.getPos();
    is.close();
  }

  /**
   * Constructor to map a file containing Succinct data structures via streams.
   *
   * @param filePath Path of the file.
   * @throws IOException
   */
  public SuccinctIndexedFileStream(Path filePath) throws IOException {
    this(filePath, new Configuration());
  }

  public int offsetToRecordId(int pos) {
    int sp = 0, ep = offsets.length - 1;
    int m;

    while (sp <= ep) {
      m = (sp + ep) / 2;
      if (offsets[m] == pos) {
        return m;
      } else if (pos < offsets[m]) {
        ep = m - 1;
      } else {
        sp = m + 1;
      }
    }

    return ep;
  }

  public int getNumRecords() {
    return offsets.length;
  }

  @Override public byte[] getRecord(int recordId) {
    if (recordId >= offsets.length || recordId < 0) {
      throw new ArrayIndexOutOfBoundsException(
        "Record does not exist: recordId = " + recordId);
    }
    int begOffset = offsets[recordId];
    int endOffset = (recordId == offsets.length - 1) ?
      getOriginalSize() - 1 :
      offsets[recordId + 1];
    int len = (endOffset - begOffset - 1);
    return extract(begOffset, len);
  }

  @Override public byte[] accessRecord(int recordId, int offset, int length) {
    if (recordId >= offsets.length || recordId < 0) {
      throw new ArrayIndexOutOfBoundsException(
        "Record does not exist: recordId = " + recordId);
    }
    int begOffset = offsets[recordId] + offset;
    return extract(begOffset, length);
  }

  public Integer[] recordSearchIds(byte[] query) {
    Set<Integer> results = new HashSet<Integer>();
    Range range = bwdSearch(query);

    long sp = range.first, ep = range.second;
    if (ep - sp + 1 <= 0) {
      return new Integer[0];
    }

    for (long i = 0; i < ep - sp + 1; i++) {
      results.add(offsetToRecordId((int) lookupSA(sp + i)));
    }

    return results.toArray(new Integer[results.size()]);
  }

  @Override public Iterator<Integer> recordSearchIdIterator(byte[] query) {
    SearchIterator searchIterator = (SearchIterator) searchIterator(query);
    return new SearchRecordIterator(searchIterator, this);
  }

  @Override public byte[][] recordSearch(byte[] query) {
    Set<Integer> recordIds = new HashSet<Integer>();
    ArrayList<byte[]> results = new ArrayList<byte[]>();
    Range range = bwdSearch(query);

    long sp = range.first, ep = range.second;
    if (ep - sp + 1 <= 0) {
      return new byte[0][0];
    }

    for (long i = 0; i < ep - sp + 1; i++) {
      long saVal = lookupSA(sp + i);
      int recordId = offsetToRecordId((int) saVal);
      if (!recordIds.contains(recordId)) {
        results.add(getRecord(recordId));
        recordIds.add(recordId);
      }
    }

    return results.toArray(new byte[results.size()][]);
  }

  @Override public byte[][] recordRangeSearch(byte[] queryBegin, byte[] queryEnd) {
    Set<Integer> recordIds = new HashSet<Integer>();
    ArrayList<byte[]> results = new ArrayList<byte[]>();
    Range rangeBegin = bwdSearch(queryBegin);
    Range rangeEnd = bwdSearch(queryEnd);

    long sp = rangeBegin.first, ep = rangeEnd.second;
    if (ep - sp + 1 <= 0) {
      return new byte[0][0];
    }

    for (long i = 0; i < ep - sp + 1; i++) {
      long saVal = lookupSA(sp + i);
      int recordId = offsetToRecordId((int) saVal);
      if (!recordIds.contains(recordId)) {
        results.add(getRecord(recordId));
        recordIds.add(recordId);
      }
    }

    return results.toArray(new byte[results.size()][]);
  }

  /**
   * Check if the two recordIds belong to the same record.
   *
   * @param firstOffset The first offset.
   * @param secondOffset The second offset.
   * @return True if the two recordIds belong to the same record, false otherwise.
   */
  @Override public boolean sameRecord(long firstOffset, long secondOffset) {
    return offsetToRecordId((int) firstOffset) == offsetToRecordId((int) secondOffset);
  }

  @Override public byte[][] recordSearchRegex(String query) throws RegExParsingException {
    Map<Long, Integer> regexOffsetResults = regexSearch(query);
    Set<Integer> recordIds = new HashSet<Integer>();
    ArrayList<byte[]> results = new ArrayList<byte[]>();
    for (Long offset : regexOffsetResults.keySet()) {
      int recordId = offsetToRecordId(offset.intValue());
      if (!recordIds.contains(recordId)) {
        results.add(getRecord(recordId));
        recordIds.add(recordId);
      }
    }
    return results.toArray(new byte[results.size()][]);
  }

  @Override public byte[][] multiSearch(QueryType[] queryTypes, byte[][][] queries) {
    assert (queryTypes.length == queries.length);
    Set<Integer> recordIds = new HashSet<Integer>();
    ArrayList<byte[]> results = new ArrayList<byte[]>();

    if (queries.length == 0) {
      throw new IllegalArgumentException("multiSearch called with empty queries");
    }

    // Get all ranges
    ArrayList<Range> ranges = new ArrayList<Range>();
    for (int qid = 0; qid < queries.length; qid++) {
      Range range;

      switch (queryTypes[qid]) {
        case Search: {
          range = bwdSearch(queries[qid][0]);
          break;
        }
        case RangeSearch: {
          byte[] queryBegin = queries[qid][0];
          byte[] queryEnd = queries[qid][1];
          Range rangeBegin, rangeEnd;
          rangeBegin = bwdSearch(queryBegin);
          rangeEnd = bwdSearch(queryEnd);
          range = new Range(rangeBegin.first, rangeEnd.second);
          break;
        }
        default: {
          throw new UnsupportedOperationException("Unsupported QueryType");
        }
      }

      if (range.second - range.first + 1 > 0) {
        ranges.add(range);
      } else {
        return new byte[0][0];
      }
    }
    int numRanges = ranges.size();

    Collections.sort(ranges, new RangeSizeComparator());

    // Populate the set of recordIds corresponding to the first range
    Range firstRange = ranges.get(0);
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    {
      long sp = firstRange.first, ep = firstRange.second;
      for (long i = 0; i < ep - sp + 1; i++) {
        long saVal = lookupSA(sp + i);
        int recordId = offsetToRecordId((int) saVal);
        recordIds.add(recordId);
        counts.put(recordId, 1);
      }
    }

    ranges.remove(firstRange);
    for (Range range : ranges) {
      long sp = range.first, ep = range.second;

      for (long i = 0; i < ep - sp + 1; i++) {
        long saVal = lookupSA(sp + i);
        int recordId = offsetToRecordId((int) saVal);
        if (recordIds.contains(recordId)) {
          counts.put(recordId, counts.get(recordId) + 1);
        }
      }
    }

    for (int recordId : recordIds) {
      if (counts.get(recordId) == numRanges) {
        results.add(getRecord(recordId));
      }
    }

    return results.toArray(new byte[results.size()][]);
  }
}
