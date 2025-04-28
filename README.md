# SpongeFS: Enabling Power-Proportional and Heterogeneity-Aware Storage in Data Centers

This repository contains the source code for the **SpongeFS** elastic distributed storage system and the **Dynamical Expansion Chain Generation** tool.

---

## SpongeFS

SpongeFS extends Hadoop Distributed File System (HDFS 3.3.6) to support **heterogeneous-aware equal-work data layout** and **dynamic elastic scaling** for cluster computing. Unlike traditional equal-work data layout implementations that assume homogeneous storage nodes, SpongeFS achieves a tradeoff between data balancing and throughput maximization while maintaining ideal power proportionality.

---

## Dynamical Expansion Chain Generation

The **Dynamical Expansion Chain Generation** module calculates the optimal order for activating and deactivating storage nodes.  
It uses two parameters:
- **α**: weight for data balancing.
- **β**: weight for write throughput maximization.

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

2. Edit `config.json` to describe the cluster nodes and parameters.

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

2. Adjust parameters `p`, `r`, `rth` in the following files if necessary:
    - `hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/server/blockmanagement/BlockPlacementPolicySpongeFS.java`
    - `hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/server/blockmanagement/BlockPlacementPolicyRabbit.java`

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

4. Incremental Compilation Tip
If you modify only BlockPlacementPolicySpongeFS.java or BlockPlacementPolicyRabbit.java, you can rebuild just the HDFS module without recompiling the entire Hadoop source:

```bash
cd spongeFS/hadoop-3.3.6-src/hadoop-hdfs-project/hadoop-hdfs/
mvn clean package -DskipTests
```

Then replace the generated JAR file:

```bash
cp target/hadoop-hdfs-3.3.6.jar $HADOOP_HOME/share/hadoop/hdfs/
```
where $HADOOP_HOME is your Hadoop installation path.


