import csv
import json
import numpy as np

# Load configuration file
def load_config(file_path):
    with open(file_path, 'r') as f:
        config = json.load(f)
    return config

# Node calculation logic
class NodeCalculations:
    def __init__(self, config):
        self.alpha = config['cluster']['alpha']
        self.beta = config['cluster']['beta']
        self.B = config['cluster']['dataset_size_GB']
        self.conversion_factor = 1.07374  # GiB to GB conversion

    def calculate_f_db(self, node, total_read_throughput):
        remaining_capacity_after_placement = node['remaining_capacity_GiB'] * self.conversion_factor - (
            node['read_throughput_MB_s'] * 1.0 / total_read_throughput) * self.B
        total_capacity = node['capacity_GiB'] * self.conversion_factor
        return remaining_capacity_after_placement / total_capacity

    def calculate_f_th(self, node, nodeList):
        new_node_write_throughput_log = np.log10(node['write_throughput_MB_s'])
        max_write_throughput_log = np.log10(max(n['write_throughput_MB_s'] for n in nodeList))
        return new_node_write_throughput_log / max_write_throughput_log

    def calculate_f(self, nodeList, new_node):
        total_read_throughput = sum(node['read_throughput_MB_s'] for node in nodeList)
        f_db = self.calculate_f_db(new_node, total_read_throughput)
        f_th = self.calculate_f_th(new_node, nodeList)
        return self.alpha * f_db + self.beta * f_th


# Node selection logic
def getBestNode(nodeOptions, chosenNodes, node_calculations):
    bestScore = float('-inf')
    bestNode = None
    for option in nodeOptions:
        chosenNodes.append(option)
        score = node_calculations.calculate_f(chosenNodes, option)
        # print(option['hostname'], ":", score)
        if score > bestScore:
            bestScore = score
            bestNode = option
        chosenNodes.remove(option)
    return bestNode

def ExpansionChainNodeEnough(p, r, chosen):
    sum_rth = sum(node['read_throughput_MB_s'] for node in chosen[:p])
    sum_data = 0
    for i in range(p, len(chosen)):
        sum_rth += chosen[i]['read_throughput_MB_s']
        sum_data += chosen[i]['read_throughput_MB_s'] / sum_rth
    return sum_data > r - 1

def GetExpansionChain(p, r, nodesList, node_calculations):
    chosen = []
    # Sort the nodes based on write throughput and remaining capacity
    nodesList.sort(key=lambda node: (node['write_throughput_MB_s'], node['remaining_capacity_GiB'] * 1.07374), reverse=True)

    # Add the top p nodes to the chosen list
    for i in range(p):
        chosen.append(nodesList[i])

    del nodesList[:p]
    while nodesList:
        bestNode = getBestNode(nodesList, chosen, node_calculations)
        chosen.append(bestNode)
        nodesList.remove(bestNode)

        # Check if the expansion chain condition is met
        if ExpansionChainNodeEnough(p, r, nodesList):
            chosen.remove(bestNode)
            break

    return chosen

# Save the results to a CSV file
def save_to_csv(expansion_chain, filename="expansion_chain_result.csv"):
    with open(filename, mode='w', newline='') as file:
        writer = csv.writer(file)
        writer.writerow(['position', 'hostname'])  # Write header
        for idx, node in enumerate(expansion_chain, 1):
            writer.writerow([idx, node['hostname']])  # Write position and hostname


if __name__ == "__main__":
    # Load configuration file
    config = load_config('config.json')
    nodesList = config['node_configurations']
    n = config['cluster']['total_nodes']
    p = config['cluster']['primary_nodes']
    r = config['cluster']['replication']

    # Initialize NodeCalculations object
    node_calculations = NodeCalculations(config)
    expansion_chain = GetExpansionChain(p, r, nodesList, node_calculations)

    # Save the results to CSV
    save_to_csv(expansion_chain)
    print("Expansion Chain saved to 'expansion_chain_result.csv'.")
