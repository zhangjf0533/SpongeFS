# SpongeFS: Enabling Power-Proportional and Heterogeneity-Aware Storage in Data Centers

This repository contains the source code for the **SpongeFS** elastic distributed storage system and the **Dynamical Expansion Chain Generation** tool.

---

## SpongeFS

SpongeFS extends Hadoop Distributed File System (HDFS 3.3.6) to support **heterogeneity-aware equal-work data layout**, **dynamic elastic scaling**, and **optional write offloading** for cluster computing. Unlike traditional equal-work data layout implementations that assume homogeneous storage nodes, SpongeFS achieves a tradeoff between data balancing and throughput maximization while maintaining ideal power proportionality. When the optional write offloading mode is enabled, excess writes on overloaded primary nodes are redirected to non-primary nodes in proportion to their spare write throughput, alleviating write bottlenecks while keeping cleanup overhead low.
---

## Dynamical Expansion Chain Generation

The **Dynamic Expansion-Chain Generation** module calculates the optimal order for activating and deactivating storage nodes. It is formulated as a multi-objective optimization problem with the following unified score:

$$f = \lambda \cdot f_{db} + (1 - \lambda) \cdot f_{em}$$

where:
- **f_db** (data balancing): prioritizes nodes with a higher free-capacity ratio after accounting for the equal-work data amount to be stored.
- **f_em** (energy minimization): prioritizes nodes with higher read throughput (log-normalized), so that the system can serve the same workload with fewer active nodes.
- **λ ∈ [0, 1]**: the weighting factor that tunes the relative importance of balanced data distribution and minimal energy consumption. Representative values used in the paper are `λ ∈ {1, 0.5, 0.3, 0}`.

The tool consists of:
- `config.json`: Defines cluster-wide parameters and node-specific characteristics.
- `GetExpansionChain.py`: Generates the expansion chain saved as `expansion_chain_result.csv`.

The expansion chain lists nodes from highest to lowest activation priority based on optimization objectives.

---

# Usage Instructions

## Prerequisites

All required packages and installation steps are provided in `install-prerequisites.txt`.

Environment requirements:
- CentOS 7.9
- JDK 1.8
- Maven 3.5.4
- Protocol Buffers 3.7.1
- CMake 3.6.0
- Zlib-devel, OpenSSL-devel, Cyrus-SASL-devel
- GCC 9.3.0 or later (or Clang)
- Python 3 with NumPy
- Node.js

Follow the detailed steps in `install-prerequisites.txt` to prepare the environment.

---

## 1. Generate Expansion Chain

1. Navigate to the expansion tool directory:
    ```bash
    cd SpongeFS/Dynamical_ExpansionChain_Generation/
    ```

2. Edit `config.json` to describe cluster-wide parameters and per-node characteristics. In particular, set:
   - `primary_nodes` (`p`): number of primary nodes (default `4`).
   - `replication` (`r`): replication factor (default `3`).
   - `blocksize_MB`: HDFS block size in MB (default `128`).
   - `dataset_size_GB` (`B`): total dataset size in GB.
   - `lambda`: weighting factor in `[0, 1]` (e.g., `1`, `0.5`, `0.3`, or `0`).
   - For each node: `capacity_GiB`, `remaining_capacity_GiB`, `read_throughput_MB_s` (`rth`), `write_throughput_MB_s` (`wth`).

3. Run the expansion chain generation script:
    ```bash
    python GetExpansionChain.py
    ```

4. The result will be saved as `expansion_chain_result.csv`.

**Note**:  
During deployment, configure node IPs according to the expansion chain order (lower position → lower IP).

---

## 2. Build SpongeFS (Modified Hadoop 3.3.6)

1. Navigate to the source directory:
    ```bash
    cd SpongeFS/hadoop-3.3.6-src/
    ```

2. Adjust parameters `p`, `r`, per-node `rth`, and per-node `wth` in the following files if necessary:
    - `hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/server/blockmanagement/BlockPlacementPolicySpongeFS.java`
    - `hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/server/blockmanagement/BlockPlacementPolicyRabbit.java`

   These values must be consistent with the `config.json` used for expansion-chain generation.

   In `BlockPlacementPolicySpongeFS.java`, two compile-time constants at the top of the class control the write offloading mode (paper §3.3):
   - `ENABLE_WRITE_OFFLOAD` (default `false`): set to `true` to enable the write offloading placement mode; set to `false` to use the pure equal-work layout only.
   - `OFFLOAD_SET_SIZE` (default `WriteOffloadPolicy.OFFLOAD_SET_SIZE_AUTO`): the total offload set size `m`.
   
3. Compile the project:
    ```bash
    mvn clean install -Pdist,native -DskipTests -Dtar
    ```

4. The compiled installation package will be located at:
    ```
    hadoop-dist/target/hadoop-3.3.6.tar.gz
    ```

5. Extract and configure like a standard Hadoop installation.

---

## 3. Configure Block Placement Policies

To use the **SpongeFS equal-work data layout policy**, add the following property to `hdfs-site.xml`:

```xml
<property>
  <name>dfs.block.replicator.classname</name>
  <value>org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicySpongeFS</value>
</property>
```
To use the **Rabbit equal-work data layout**, configure:
```xml
<property>
  <name>dfs.block.replicator.classname</name>
  <value>org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicyRabbit</value>
</property>
```

---

## 4. Incremental Compilation Tip

If you modify only `BlockPlacementPolicySpongeFS.java`, `BlockPlacementPolicyRabbit.java`, or `WriteOffloadPolicy.java`, you can rebuild just the HDFS module without recompiling the entire Hadoop source:

```bash
cd SpongeFS-src/hadoop-hdfs-project/hadoop-hdfs/
mvn clean package -DskipTests
```

Then replace the generated JAR file:

```bash
cp target/hadoop-hdfs-3.3.6.jar $HADOOP_HOME/share/hadoop/hdfs/
```
where `$HADOOP_HOME` is your Hadoop installation path. Restart HDFS for the new policy to take effect.


