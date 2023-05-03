import pandas as pd

df = pd.read_csv('./invocations_per_function_md.anon.d10.csv')
num_rows = 100
min_count = 10
min_val = 5
output_df = pd.DataFrame()

# Dropping off unnecessary columns
df = df.drop(labels=['HashOwner', 'HashApp', 'Trigger'] + [str(i) for i in range(31, 1441)], axis=1)
df = df.reset_index(drop=True)
cols = [str(i) for i in range(1, 31)]

# initialize the list of valid rows
valid_rows = []

for col in cols:
    col_filtered = df.loc[(df[col].between(1, 5)) & (df.loc[:, '1':'30'] <= 5).all(axis=1)]

    if len(col_filtered) < 10:
        print(f"missing col {col}")
        continue
    valid_rows.extend(col_filtered.sample(n=10).index.tolist())

sampled_df = df.loc[valid_rows].sample(n=50)
sampled_df.to_csv('./bigger_trimmer_dataset.csv')

sums = sampled_df.loc[:, cols].sum()
print(sums)
max_col = sums.idxmax()

# Get the maximum sum
max_sum = sums[max_col]

# Create a new dataframe with the maximum sum and the corresponding column name
result = pd.DataFrame({'Column with Max Sum': [max_col], 'Max Sum': [max_sum]})

# Print the result
print(result)