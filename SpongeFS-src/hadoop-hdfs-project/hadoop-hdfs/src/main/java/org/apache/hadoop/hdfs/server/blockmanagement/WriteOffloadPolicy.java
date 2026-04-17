/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import org.apache.hadoop.classification.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Write Offloading Policy
 * Formulae:
 * <pre>
 *   T_w*     = B * r * blocksize / sum_j wth_j                    (Eq. 8)
 *   wth_i*   = b_i * blocksize / T_w*                             (Eq. 9)
 *   b_i      = B * rth_i / sum_{j=1..k} rth_j                     (Eq. 1/2)
 *   offload  = redirect with probability max(0, 1 - wth_i/wth_i*)
 * </pre>
 * <p>
 * Thread-safe.
 */
@InterfaceAudience.Private
public final class WriteOffloadPolicy {

  public static final Logger LOG =
      LoggerFactory.getLogger(WriteOffloadPolicy.class);

  /**
   * Sentinel value for {@link #WriteOffloadPolicy(int, int, double[],
   * double[], boolean, int)}: when the {@code fixedOffloadSetSize}
   * argument equals this value, the policy auto-computes the minimal
   * offload set that absorbs the full primary overload.
   */
  public static final int OFFLOAD_SET_SIZE_AUTO = -1;

  // -------------------------------------------------------------------------
  //  Immutable configuration
  // -------------------------------------------------------------------------
  private final int p;
  private final int r;
  private final double[] rth;
  private final double[] wth;
  private final double[] idealWth;        // wth_i^*
  private final int[]    blueShadedNodes; // offload set \ primaries
  private final double[] blueWeights;     // proportional to spare wth
  private final int      offloadSetSize;  // p + blueShadedNodes.length

  /** Master switch. */
  private volatile boolean enabled;

  // -------------------------------------------------------------------------
  //  Metrics (paper Fig. 7(c))
  // -------------------------------------------------------------------------
  private final AtomicLong offloadedBlocks = new AtomicLong(0);
  private final AtomicLong offloadedBytes  = new AtomicLong(0);

  // -------------------------------------------------------------------------
  //  Construction
  // -------------------------------------------------------------------------

  /**
   * Convenience constructor: offload set size is auto-computed.
   */
  public WriteOffloadPolicy(int p, int r,
                            double[] rth, double[] wth,
                            boolean enabled) {
    this(p, r, rth, wth, enabled, OFFLOAD_SET_SIZE_AUTO);
  }

  /**
   * Full constructor.
   *
   * @param p                   number of primary nodes
   * @param r                   replication factor
   * @param rth                 per-node read  throughput (MB/s),
   *                            expansion-chain order
   * @param wth                 per-node write throughput (MB/s),
   *                            expansion-chain order
   * @param enabled             whether offloading is applied at runtime
   * @param fixedOffloadSetSize total offload set size including primaries.
   */
  public WriteOffloadPolicy(int p, int r,
                            double[] rth, double[] wth,
                            boolean enabled,
                            int fixedOffloadSetSize) {
    if (rth == null || wth == null || rth.length != wth.length) {
      throw new IllegalArgumentException(
          "rth and wth must be non-null and have identical length");
    }
    if (p <= 0 || p >= rth.length) {
      throw new IllegalArgumentException(
          "invalid p=" + p + " for cluster size " + rth.length);
    }
    if (fixedOffloadSetSize != OFFLOAD_SET_SIZE_AUTO) {
      if (fixedOffloadSetSize < p || fixedOffloadSetSize > rth.length) {
        throw new IllegalArgumentException(
            "fixedOffloadSetSize=" + fixedOffloadSetSize
            + " must be in [p=" + p + ", N=" + rth.length + "]");
      }
    }

    this.p       = p;
    this.r       = r;
    this.rth     = rth.clone();
    this.wth     = wth.clone();
    this.enabled = enabled;

    int n = rth.length;

    // ---- Ideal write throughput wth_i^* (paper Eq. 9) -------------------
    double sumWth = 0.0;
    for (double v : wth) sumWth += v;

    this.idealWth = new double[n];
    double sumPrimaryRth = 0.0;
    for (int i = 0; i < p; i++) sumPrimaryRth += rth[i];
    for (int i = 0; i < p; i++) {
      idealWth[i] = (rth[i] / sumPrimaryRth) * sumWth / r;
    }
    double cumRth = sumPrimaryRth;
    for (int i = p; i < n; i++) {
      cumRth += rth[i];
      idealWth[i] = (rth[i] / cumRth) * sumWth / r;
    }

    // ---- Spare throughput per node --------------------------------------
    double[] spare = new double[n];
    for (int i = 0; i < n; i++) {
      spare[i] = Math.max(0.0, wth[i] - idealWth[i]);
    }

    Integer[] cand = new Integer[n - p];
    for (int i = 0; i < cand.length; i++) cand[i] = p + i;
    Arrays.sort(cand, (a, b) -> Double.compare(spare[b], spare[a]));

    List<Integer> blue = new ArrayList<>();
    if (fixedOffloadSetSize == OFFLOAD_SET_SIZE_AUTO) {
      // Mode A: auto — minimal coverage of primary overload
      double primaryOverload = 0.0;
      for (int i = 0; i < p; i++) {
        double over = idealWth[i] - wth[i];
        if (over > 0) primaryOverload += over;
      }
      if (primaryOverload > 0) {
        double acc = 0.0;
        for (int idx : cand) {
          if (spare[idx] <= 0.0) break;
          blue.add(idx);
          acc += spare[idx];
          if (acc >= primaryOverload) break;
        }
      }
    } else {
      // Mode B: fixed — take exactly (fixedOffloadSetSize - p) non-primary
      int take = fixedOffloadSetSize - p;
      for (int i = 0; i < take && i < cand.length; i++) {
        blue.add(cand[i]);
      }
    }

    this.blueShadedNodes = blue.stream().mapToInt(Integer::intValue).toArray();
    this.offloadSetSize  = p + blueShadedNodes.length;

    // ---- Normalized weights for blue-shaded selection -------------------
    this.blueWeights = new double[blueShadedNodes.length];
    double wSum = 0.0;
    for (int i = 0; i < blueShadedNodes.length; i++) {
      blueWeights[i] = Math.max(spare[blueShadedNodes[i]], 1e-9);
      wSum += blueWeights[i];
    }
    if (wSum > 0) {
      for (int i = 0; i < blueWeights.length; i++) blueWeights[i] /= wSum;
    } else if (blueWeights.length > 0) {
      Arrays.fill(blueWeights, 1.0 / blueWeights.length);
    }

    double primaryOverloadForLog = 0.0;
    for (int i = 0; i < p; i++) {
      double over = idealWth[i] - wth[i];
      if (over > 0) primaryOverloadForLog += over;
    }
    LOG.info("WriteOffloadPolicy initialized: enabled={}, p={}, r={}, "
            + "N={}, mode={}, offloadSetSize(m)={}, blueShaded={}, "
            + "primaryOverload={} MB/s",
        enabled, p, r, n,
        (fixedOffloadSetSize == OFFLOAD_SET_SIZE_AUTO ? "auto" : "fixed"),
        offloadSetSize,
        Arrays.toString(blueShadedNodes),
        String.format("%.1f", primaryOverloadForLog));
  }

  // -------------------------------------------------------------------------
  //  Runtime toggle
  // -------------------------------------------------------------------------
  public boolean isEnabled() { return enabled; }

  public void setEnabled(boolean e) {
    this.enabled = e;
    LOG.info("WriteOffloadPolicy enabled set to {}", e);
  }

  // -------------------------------------------------------------------------
  //  Primary placement decision (called once per block in chooseTarget)
  // -------------------------------------------------------------------------

  /**
   * Decide where the primary replica of this block should actually be
   * written.
   *
   * @param primaryIdx     expansion-chain index of the nominal primary
   * @param blockSizeBytes block size in bytes (used for the
   *                       cleanup-bytes counter)
   * @return expansion-chain index of the actual target node
   */
  public int decidePrimaryTarget(int primaryIdx, long blockSizeBytes) {
    if (!enabled)                          return primaryIdx;
    if (blueShadedNodes.length == 0)       return primaryIdx;
    if (primaryIdx < 0 || primaryIdx >= p) return primaryIdx;

    // Only the EXCESS fraction is offloaded:
    //   offloadProb = (idealWth - wth) / idealWth
    double excess = idealWth[primaryIdx] - wth[primaryIdx];
    if (excess <= 0.0)                     return primaryIdx;
    double prob = excess / idealWth[primaryIdx];

    if (ThreadLocalRandom.current().nextDouble() >= prob) {
      return primaryIdx;
    }

    int blueIdx = weightedPick(blueShadedNodes, blueWeights);
    offloadedBlocks.incrementAndGet();
    offloadedBytes.addAndGet(blockSizeBytes);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Offload: primary#{} -> blue#{} (prob={})",
          primaryIdx, blueIdx, String.format("%.3f", prob));
    }
    return blueIdx;
  }


  /** @return total offload set size m (= p + |blueShaded|). */
  public int getOffloadSetSize() {
    return offloadSetSize;
  }

  public int[] getOffloadSet() {
    int[] out = new int[offloadSetSize];
    for (int i = 0; i < p; i++) out[i] = i;
    System.arraycopy(blueShadedNodes, 0, out, p, blueShadedNodes.length);
    return out;
  }

  public int[]    getBlueShadedNodes()   { return blueShadedNodes.clone(); }
  public double[] getIdealWth()          { return idealWth.clone(); }
  public int      getNumPrimaryNodes()   { return p; }
  public int      getReplicationFactor() { return r; }

  /** @return cumulative offloaded bytes (Fig. 7(c) metric). */
  public long getOffloadedBytes()   { return offloadedBytes.get(); }
  /** @return cumulative offloaded block count. */
  public long getOffloadedBlocks()  { return offloadedBlocks.get(); }

  public void resetMetrics() {
    offloadedBytes.set(0);
    offloadedBlocks.set(0);
  }

  private static int weightedPick(int[] indices, double[] weights) {
    double r = ThreadLocalRandom.current().nextDouble();
    double acc = 0.0;
    for (int i = 0; i < indices.length; i++) {
      acc += weights[i];
      if (r < acc) return indices[i];
    }
    return indices[indices.length - 1];
  }
}
