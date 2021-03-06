import java.util.LinkedList;
import java.util.ArrayList;

/*
 * implement a simple linked list that can efficient concatenate two lists.
 */
public class SqtQuadTreeGeoRequestParser extends GeoRequestParser{

  public static final long MAX_RESOLUTION = 28L;
  private static final long X_LEFT_SHIFT = MAX_RESOLUTION;
  private static final long R_LEFT_SHIFT = MAX_RESOLUTION << 1;
  private static final long COOR_MASK = (1L << MAX_RESOLUTION) - 1;

  private long maxResolution;
  private double maxXTileLen;
  private double maxYTileLen;
  private double minXTileLen;
  private double minYTileLen;


  // key:
  //  first 8 bits: resolution
  //  next 28 bits: x
  //  last 28 bits: y
  LruCache<Long, LinkedList<Range> > cache = null; 

  // TODO: QuadTree only applies to a subset of GeoEncoding
  // algorithms. StripGeoEncoding is a counter example.
  // Apply checks during construction.
  public SqtQuadTreeGeoRequestParser(GeoEncoding ge, int maxEntries) {
    super(ge);
    this.maxResolution = this.gc.getMaxResolution();
    if (this.maxResolution > MAX_RESOLUTION) {
      throw new IllegalStateException("Encoding resolution " 
          + this.maxResolution + " is larger than MAX_RESOLUTION "
          + MAX_RESOLUTION);
    }
    this.maxXTileLen = gc.getMaxX();
    this.maxYTileLen = gc.getMaxY();
    this.minXTileLen = 
      this.maxXTileLen / (1L << this.maxResolution);
    this.minYTileLen = 
      this.maxYTileLen / (1L << this.maxResolution);
    this.cache = new LruCache<Long, LinkedList<Range> > (maxEntries);
  }

  @Override
  public LinkedList<Range> getScanRanges(GeoRequest gr) {
    return internalGetScanRanges(gr, 0, 0, 
        this.maxXTileLen, this.maxYTileLen, 0);
  }

  private long getKey(long xTile, long yTile, long r) {
    return (r << R_LEFT_SHIFT) 
           | ((xTile & COOR_MASK) << X_LEFT_SHIFT) 
           | (yTile & COOR_MASK);
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
      Long key = new Long(getKey(xTile, yTile, curResolution));
      LinkedList<Range> res = this.cache.get(key);
      if (null == res) {
        res = this.ge.getTileRange(xTile, yTile, curResolution);
        this.cache.put(key, res);
      }
      return res;
    }

    // max resolution reached.
    if (curResolution >= this.maxResolution 
        || curResolution >= gr.getResolution()) {
      Long key = new Long(getKey(xTile, yTile, curResolution));
      LinkedList<Range> res = this.cache.get(key);
      if (null == res) {
        res = this.ge.getTileRange(xTile, yTile, curResolution);
        this.cache.put(key, res);
      }
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
    if (null != nw && nw.size() > 0) {
      ranges[rangesLen] = nw;
      ++rangesLen;
    }
    if (null != ne && ne.size() > 0) {
      ranges[rangesLen] = ne;
      ++rangesLen;
    }
    if (null != sw && sw.size() > 0) {
      ranges[rangesLen] = sw;
      ++rangesLen;
    }
    if (null != se && se.size() > 0) {
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
