import csv
import json
import numpy as np


# ---------------------------------------------------------------------------
# Load configuration file
# ---------------------------------------------------------------------------
def load_config(file_path):
    with open(file_path, 'r') as f:
        config = json.load(f)
    return config


# ---------------------------------------------------------------------------
# Node scoring logic (implements Eq. (7) in the SpongeFS paper):
#   f = lambda * f_db + (1 - lambda) * f_em
# where
#   f_db : data balancing score, prioritizes nodes with higher free-capacity
#          ratio after the equal-work amount is placed.
#   f_em : energy minimization score, prioritizes nodes with higher read
#          throughput so that fewer active nodes are needed to serve the
#          same workload.
# ---------------------------------------------------------------------------
class NodeCalculations:
    def __init__(self, config):
        # Single weighting factor lambda in [0, 1].
        self.lam = config['cluster']['lambda']
        # Total dataset size in GB (only one replica is counted here, because
        # f_db evaluates the per-node placement of a single replica).
        self.B = config['cluster']['dataset_size_GB']
        # 1 GiB = 1.073741824 GB (keep the original unit conversion).
        self.conversion_factor = 1.07374

    def calculate_f_db(self, node, total_read_throughput):
        """
        Data-balancing score (Eq. (4) in the paper):
            f_db = (rem_k - (rth_k / sum_{j=1..k} rth_j) * B) / cap_k
        Here (rth_k / total_read_throughput) * B represents the equal-work
        data amount to be placed on the candidate node (in GB).
        """
        placed_data_GB = (node['read_throughput_MB_s'] * 1.0 /
                          total_read_throughput) * self.B
        remaining_after_placement = (node['remaining_capacity_GiB'] *
                                     self.conversion_factor - placed_data_GB)
        total_capacity_GB = node['capacity_GiB'] * self.conversion_factor
        return remaining_after_placement / total_capacity_GB

    def calculate_f_em(self, node, nodeList):
        """
        Energy-minimization score (Eq. (5) in the paper):
            f_em = log(rth_k) / log(max_x rth_x)
        Uses READ throughput, since the score reflects how quickly the node
        can serve read workloads under the heterogeneity-aware equal-work
        layout.
        """
        rth_k_log = np.log10(node['read_throughput_MB_s'])
        max_rth_log = np.log10(max(n['read_throughput_MB_s'] for n in nodeList))
        return rth_k_log / max_rth_log

    def calculate_f(self, nodeList, new_node):
        """
        Unified score (Eq. (7) in the paper):
            f = lambda * f_db + (1 - lambda) * f_em
        """
        total_read_throughput = sum(n['read_throughput_MB_s'] for n in nodeList)
        f_db = self.calculate_f_db(new_node, total_read_throughput)
        f_em = self.calculate_f_em(new_node, nodeList)
        return self.lam * f_db + (1.0 - self.lam) * f_em


# ---------------------------------------------------------------------------
# Greedy node selection
# ---------------------------------------------------------------------------
def getBestNode(nodeOptions, chosenNodes, node_calculations):
    bestScore = float('-inf')
    bestNode = None
    for option in nodeOptions:
        chosenNodes.append(option)
        score = node_calculations.calculate_f(chosenNodes, option)
        if score > bestScore:
            bestScore = score
            bestNode = option
        chosenNodes.remove(option)
    return bestNode


def ExpansionChainNodeEnough(p, r, chosen):
    """
    Check whether the current chain already stores (r-1) replicas of the
    dataset on non-primary nodes (Eq. (6) in the paper):
        sum_{i=p+1}^{s} (rth_i / sum_{j=1..i} rth_j) >= r - 1
    """
    sum_rth = sum(node['read_throughput_MB_s'] for node in chosen[:p])
    sum_data = 0.0
    for i in range(p, len(chosen)):
        sum_rth += chosen[i]['read_throughput_MB_s']
        sum_data += chosen[i]['read_throughput_MB_s'] / sum_rth
    return sum_data > r - 1


def GetExpansionChain(p, r, nodesList, node_calculations):
    chosen = []

    # Step 1: pick the top-p nodes with the highest write throughput as
    # primary nodes (ties broken by larger remaining capacity).
    nodesList.sort(
        key=lambda node: (node['write_throughput_MB_s'],
                          node['remaining_capacity_GiB'] * 1.07374),
        reverse=True,
    )
    for i in range(p):
        chosen.append(nodesList[i])
    del nodesList[:p]

    # Step 2: greedily append non-primary nodes until (r-1) replicas of
    # the dataset can be fully stored on them.
    while nodesList:
        bestNode = getBestNode(nodesList, chosen, node_calculations)
        chosen.append(bestNode)
        nodesList.remove(bestNode)

        # NOTE: evaluate the condition on the CHOSEN chain, not the
        # remaining candidates.
        if ExpansionChainNodeEnough(p, r, chosen):
            break

    return chosen


# ---------------------------------------------------------------------------
# Save results
# ---------------------------------------------------------------------------
def save_to_csv(expansion_chain, filename="expansion_chain_result.csv"):
    with open(filename, mode='w', newline='') as file:
        writer = csv.writer(file)
        writer.writerow([
            'position', 'hostname', 'capacity_GiB',
            'read_MB_s', 'write_MB_s'
        ])
        for idx, node in enumerate(expansion_chain, 1):
            writer.writerow([
                idx,
                node['hostname'],
                node['capacity_GiB'],
                node['read_throughput_MB_s'],
                node['write_throughput_MB_s'],
            ])


if __name__ == "__main__":
    config = load_config('config.json')
    nodesList = config['node_configurations']
    p = config['cluster']['primary_nodes']
    r = config['cluster']['replication']

    node_calculations = NodeCalculations(config)
    expansion_chain = GetExpansionChain(p, r, nodesList, node_calculations)

    save_to_csv(expansion_chain)
    print("Expansion Chain (length = {}) saved to "
          "'expansion_chain_result.csv'.".format(len(expansion_chain)))