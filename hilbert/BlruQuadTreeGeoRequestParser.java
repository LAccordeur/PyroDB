import java.util.LinkedList;
import java.util.ArrayList;

/*
 * implement a simple linked list that can efficient concatenate two lists.
 *
 * 0. cache needs to be static element
 * 1. use loop rather than recursion
 * 2. only maintain a lru list of all leaf nodes
 * 3. upon eviction reset all visit counters.
 * 4. maintain a counter for all pending leaves, materialize one when it
 * exceeds the threshold
 * 5. one geo-request materialize at most one block.
 * 6. how to pick on to eject? 
 *
 * 1. insertion maintains a max heap. when the top element surpasses the 
 * threshold, materialize it.
 * 1.1. the heap has to have a fixed size, otherwise it may grow too large. 
 *      maintain another lru to evict heap elements. 
 * 1.2 if too costly, randomize to pick only a fraction of the ranges to pass
 *     pass down.
 * 2. eviction uses lru on all leaf node. 
 */
public class QuadTreeGeoRequestParser extends GeoRequestParser{

  private long maxResolution;
  private double maxXTileLen;
  private double maxYTileLen;
  private double minXTileLen;
  private double minYTileLen;


  // TODO: QuadTree only applies to a subset of GeoEncoding
  // algorithms. StripGeoEncoding is a counter example.
  // Apply checks during construction.
  public QuadTreeGeoRequestParser(GeoEncoding ge) {
    super(ge);
    this.maxResolution = this.gc.getMaxResolution();
    this.maxXTileLen = gc.getMaxX();
    this.maxYTileLen = gc.getMaxY();
    this.minXTileLen = 
      this.maxXTileLen / (1L << this.maxResolution);
    this.minYTileLen = 
      this.maxYTileLen / (1L << this.maxResolution);
  }

  @Override
  public LinkedList<Range> getScanRanges(GeoRequest gr) {
    return internalGetScanRanges(gr, 0, 0, 
        this.maxXTileLen, this.maxYTileLen, 0);
  }

  private LinkedList<Range> internalGetScanRanges(GeoRequest gr, 
      long xTile, long yTile, double xTileLen, double yTileLen, 
      long curResolution) {
    // check if it is a full cover or not cover.
    int isCovered = gr.isCovered(xTile * this.minXTileLen, 
                                 yTile * this.minYTileLen, 
                                 xTileLen, yTileLen);
    if (GeoRequest.NO_COVER == isCovered) {
      // not covered
      return null;
    } else if (GeoRequest.FULL_COVER == isCovered) {
      // full cover
      LinkedList<Range> res = new LinkedList<Range>();
      res.addAll(this.ge.getTileRange(xTile, yTile, curResolution));
      return res;
    }

    // max resolution reached.
    if (curResolution >= this.maxResolution 
        || curResolution >= gr.getResolution()) {
      LinkedList<Range> res = new LinkedList<Range>();
      res.addAll(this.ge.getTileRange(xTile, yTile, curResolution));
      return res;
    }

    double halfXLen = xTileLen / 2;
    double halfYLen = yTileLen / 2;
    long nextResolution = curResolution + 1;
    long mask = 1L << (this.maxResolution - nextResolution); 


    LinkedList<Range> nw = internalGetScanRanges(gr,
        xTile, yTile | mask, halfXLen, halfYLen, nextResolution);
    LinkedList<Range> ne = internalGetScanRanges(gr,
        xTile | mask, yTile | mask, halfXLen, halfYLen, nextResolution);
    LinkedList<Range> sw = internalGetScanRanges(gr,
        xTile, yTile, halfXLen, halfYLen, nextResolution);
    LinkedList<Range> se = internalGetScanRanges(gr,
        xTile | mask, yTile, halfXLen, halfYLen, nextResolution);

    return mergeRanges(nw, ne, sw, se);
  }

  private LinkedList<Range> mergeRanges(LinkedList<Range> nw,
      LinkedList<Range> ne, LinkedList<Range> sw, LinkedList<Range> se) {
    int i, j;
    LinkedList<Range> tmp;
    LinkedList<Range> [] ranges = new LinkedList[4];
    int rangesLen = 0;
    if (null != nw) {
      ranges[rangesLen] = nw;
      ++rangesLen;
    }
    if (null != ne) {
      ranges[rangesLen] = ne;
      ++rangesLen;
    }
    if (null != sw) {
      ranges[rangesLen] = sw;
      ++rangesLen;
    }
    if (null != se) {
      ranges[rangesLen] = se;
      ++rangesLen;
    }
    if (rangesLen <= 0)
      return null;
    // sort
    for (i = 1; i < rangesLen; ++i) {
      for (j = rangesLen - 1; j >= i; --j) {
        if (ranges[j].getFirst().getStart() 
            < ranges[j-1].getFirst().getStart()) {
          tmp = ranges[j];
          ranges[j] = ranges[j-1];
          ranges[j-1] = tmp;
        }
      }
    }

    // merge
    LinkedList<Range> res = ranges[0];
    for (i = 1; i < rangesLen; ++i) {
      if (res.getLast().isConsecutive(ranges[i].getFirst())) {
        res.getLast().spanTo(ranges[i].getFirst());
        ranges[i].remove(0);
      }
      res.addAll(ranges[i]);

    }
    return res;
  }
}
