import numpy as np
import matplotlib.pyplot as plt
import re
import os
import inequalipy as ineq

dir_names = ['consistent_hashing_logs_new', 'greedy_logs_new', 'weighted_dist_logs_new']

pattern1 = r"Current invoker energy map :=: (.*)$"
pattern2 = r"Current invoker excess energy map :=: (.*)$"
pattern3 = r"Current invoker distribution map :=: (.*)$"

value_dict = {}


def extract_invoker_map(input_string):
    # Split the input string into two parts based on the :=: separator

    invoker_data = input_string.strip()
    invoker_data_string = invoker_data[4: -1]

    if not invoker_data_string:
        return None

    # Split the invoker map string into a list of key-value pairs
    invoker_map_list = invoker_data_string.split(", ")
    # Initialize an empty dictionary to hold the final output
    invoker_map = {}

    if len(invoker_map_list) != 9:
        return None
    # Loop through each key-value pair in the list
    for pair in invoker_map_list:
        try:
            # Extract the invoker number from the key
            invoker_num = int(pair.split("->")[0].split("/")[1])
        except IndexError:
            invoker_num = int(pair.split("->")[0].strip()[-1])
            # Extract the value and convert it to a float
        value = float(pair.split(" -> ")[1])
        # Add the key-value pair to the dictionary
        invoker_map[invoker_num] = value

    return invoker_map


def get_values_from_file(file):
    energy_map_array = []
    dist_map_array = []
    excess_energy_map_array = []
    with open(file, 'r') as control:
        for line in control:
            result1 = re.search(pattern1, line)
            if result1:
                extracted_str = result1.group(1)
                energy_value = extract_invoker_map(extracted_str)
                if energy_value:
                    energy_map_array.append(energy_value)

            result2 = re.search(pattern2, line)
            if result2:
                extracted_str = result2.group(1)
                excess_energy_value = extract_invoker_map(extracted_str)
                if excess_energy_value:
                    excess_energy_map_array.append(excess_energy_value)

            result3 = re.search(pattern3, line)
            if result3:
                extracted_str = result3.group(1)
                dist_value = extract_invoker_map(extracted_str)
                if dist_value:
                    dist_map_array.append(dist_value)

    return energy_map_array, dist_map_array, excess_energy_map_array


def get_avg_energy_values(server_map):
    energy_values = []
    for item in server_map:
        values = list(item.values())
        energy_values.append(np.mean(values))
    return energy_values


def get_gini_values(server_map):
    gini_values = []
    for item in server_map:
        values = list(item.values())
        sorted_values = np.sort(values)
        gi = ineq.gini(sorted_values)
        gini_values.append(gi)
    return gini_values


def get_cv_values(server_map):
    cv_values = []
    for item in server_map:
        values = list(item.values())
        values = np.sort(values)

        mean = np.mean(values)
        std_dev = np.std(values)
        if mean == 0:
            cv_values.append(0)
        else:
            cv_values.append(std_dev / mean)
    return cv_values


avg_energy_map = {}
gini_energy_map = {}
coeff_variation_energy_map = {}

gini_dist_map = {}
coeff_variation_dist_map = {}

for name in dir_names:
    controller_file_path = f'./{name}/controller0_logs.log'
    energy_map, dist_map, excess_energy_map = get_values_from_file(controller_file_path)

    avg_energy_values = get_avg_energy_values(energy_map)
    avg_energy_map[name] = avg_energy_values

    gini_energy_values = get_gini_values(energy_map)
    gini_energy_map[name] = gini_energy_values

    cv_energy_values = get_cv_values(energy_map)
    coeff_variation_energy_map[name] = cv_energy_values

    gini_dist_values = get_gini_values(dist_map)
    gini_dist_map[name] = gini_dist_values

    cv_dist_values = get_cv_values(dist_map)
    coeff_variation_dist_map[name] = cv_dist_values

if not os.path.isdir('./fairness_graphs_new'):
    os.mkdir('./fairness_graphs_new/')

# Plot avg energy plots
for key in avg_energy_map.keys():
    # Create the plot
    plt.plot(range(len(avg_energy_map[key])), avg_energy_map[key], label=key)

# Add labels and legend
plt.title('Avg energy across all invokers')
plt.xlabel('Time period in minutes')
plt.ylabel('Avg energy')
plt.legend()

# Save the plot to a file
plt.savefig('./fairness_graphs_new/avg_energy_graph.png')
plt.close()

# Plot gini index energy plots
for key in gini_energy_map.keys():
    # Create the plot
    plt.plot(range(len(gini_energy_map[key])), gini_energy_map[key], label=key)

# Add labels and legend
plt.title('Energy gini index across all invokers')
plt.xlabel('Time period in minutes')
plt.ylabel('Gini Index')
plt.legend()

# Save the plot to a file
plt.savefig('./fairness_graphs_new/gini_index_energy_graph.png')
plt.close()

# Plot coeff of variation energy plots
for key in coeff_variation_energy_map.keys():
    # Create the plot
    plt.plot(range(len(coeff_variation_energy_map[key])), coeff_variation_energy_map[key], label=key)

# Add labels and legend
plt.title('Coefficient of variation across all invokers')
plt.xlabel('Time period in minutes')
plt.ylabel('Coefficient of variation')
plt.legend()

# Save the plot to a file
plt.savefig('./fairness_graphs_new/cv_energy_graph.png')
plt.close()

# Plot gini index of function distribution plots
for key in gini_dist_map.keys():
    # Create the plot
    plt.plot(range(len(gini_dist_map[key])), gini_dist_map[key], label=key)

# Add labels and legend
plt.title('Function distribution gini index across all invokers')
plt.xlabel('Time period in minutes')
plt.ylabel('Function distribution')
plt.legend()

# Save the plot to a file
plt.savefig('./fairness_graphs_new/gini_index_dist_graph.png')
plt.close()

# Plot gini index energy plots
for key in coeff_variation_dist_map.keys():
    # Create the plot
    plt.plot(range(len(coeff_variation_dist_map[key])), coeff_variation_dist_map[key], label=key)

# Add labels and legend
plt.title('CV of function distribution across all invokers')
plt.xlabel('Time period in minutes')
plt.ylabel('Coefficient of variation')
plt.legend()

# Save the plot to a file
plt.savefig('./fairness_graphs_new/cv_dist_graph.png')
plt.close()
