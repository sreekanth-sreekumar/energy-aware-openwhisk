from collect_fairness import dir_names, get_values_from_file
import os
import matplotlib.pyplot as plt

if __name__ == '__main__':

    if not os.path.isdir('./sanity_checks_new'):
        os.mkdir('./sanity_checks_new/')

    for name in dir_names:
        controller_file_path = f'./{name}/controller0_logs.log'
        energy_map, dist_map, excess_energy_map = get_values_from_file(controller_file_path)

        # Extracting energy data on each invoker
        invoker_energy_map = {}
        for item in energy_map:
            for server in item.keys():
                if server in invoker_energy_map.keys():
                    invoker_energy_map[server].append(item[server])
                else:
                    invoker_energy_map[server] = [item[server]]

        # Extracting func dist data on each invoker
        invoker_dist_map = {}
        for item in dist_map:
            for server in item.keys():
                if server in invoker_dist_map.keys():
                    invoker_dist_map[server].append(item[server])
                else:
                    invoker_dist_map[server] = [item[server]]

        plt.figure(figsize=(10,10))
        for key in invoker_energy_map.keys():
            plt.plot(range(len(invoker_energy_map[key])), invoker_energy_map[key], label=f"invoker{key}")

        plt.title(f'Invoker Energy for {name}')
        plt.xlabel('Time period in minutes')
        plt.ylabel('Energy')
        plt.legend(loc='upper right', bbox_to_anchor=(1.1, 1))

        # Save the plot to a file
        plt.savefig(f'./sanity_checks_new/invoker_energy_graph_{name}.png')
        plt.close()

        plt.figure(figsize=(10,10))
        for key in invoker_dist_map.keys():
            plt.plot(range(len(invoker_dist_map[key])), invoker_dist_map[key], label=f"invoker{key}")

        plt.title(f'Invoker Function distribution for {name}')
        plt.xlabel('Time period in minutes')
        plt.ylabel('Func Dist')
        plt.legend(loc='upper right', bbox_to_anchor=(1.1, 1))

        # Save the plot to a file
        plt.savefig(f'./sanity_checks_new/invoker_func_dist_graph_{name}.png')
        plt.close()
