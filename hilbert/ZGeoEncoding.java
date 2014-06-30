import java.util.Arrays;
import java.util.LinkedList;

public class ZGeoEncoding extends GeoEncoding {

  private long maxResolution;

  public ZGeoEncoding(GeoContext gc) {
    super(gc);
    this.maxResolution = gc.getMaxResolution();
  }

  @Override
  public long encode(long x, long y) {
    return encode(x, y, maxResolution);
  }

  public long encode(long x, long y, long r) {
    return ZCurve.staticEncode(x, y, r);
  }

  @Override
  public LinkedList<Range> getTileRange(long x, long y, long r) {
    if (this.maxResolution < r) {
      throw new IllegalStateException("Max allowed resolution is " 
          + this.maxResolution + ", got resolution " + r);
    }

    long nZeros = this.maxResolution - r;
    long mask = (1L << nZeros) - 1;
    LinkedList<Range> res = new LinkedList<Range>();
    res.add(new Range(encode(x, y, this.maxResolution), 
                      encode(x | mask, y | mask, this.maxResolution))); 
    return res;
  }

  /**
   * For testing
   */
  @Override
  public Pair<Long, Long> getNextTile(long x, long y, long r) {
    long nZeros = this.maxResolution - r;
    long unit = 1L << nZeros;
    long h = encode(x, y, r) + unit;

    return  ZCurve.staticDecode(h, r);
  }
}