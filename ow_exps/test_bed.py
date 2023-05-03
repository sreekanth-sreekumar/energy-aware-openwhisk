import subprocess
import pandas as pd
import datetime
import asyncio
from time import sleep


async def invoke_function(times, function_name):
    async def run_subprocess():
        print(f'Running subprocess for: {function_name}')
        await asyncio.create_subprocess_exec(
            "wsk", "action", "invoke", function_name, "--insecure",
        )

    tasks = []
    for i in range(times):
        tasks.append(asyncio.create_task(run_subprocess()))
        if i < times - 1:
            await asyncio.sleep(60 / times)
    await asyncio.gather(*tasks)


async def main():
    print('Started Main')
    df = pd.read_csv('/home/sreekanth/Projects/energy-aware-openwhisk/ow_exps/bigger_trimmer_dataset.csv')
    apihost = "https://192.168.0.1:444"

    subprocess.run(["wsk", "property", "set", "--apihost", apihost])

    for _, row in df.iterrows():
        function_name = row['HashFunction']
        subprocess.run(["wsk", "action", "create", function_name, "test1.js", "--insecure"])

    start_min = 1

    for i in range(start_min, 30):
        currTime = datetime.datetime.now()
        coros = []
        for _, row in df.iterrows():
            if row[[f"{i}"]].iloc[0] > 0:
                print(f"Function {row['HashFunction']} sent for execution")
                coros.append(invoke_function(row[f"{i}"], row['HashFunction']))

        await asyncio.gather(*coros)

        timeNow = datetime.datetime.now()
        diff = timeNow - currTime
        rem_sleep = 60 - diff.seconds if diff.seconds <= 60 else 0
        sleep(rem_sleep)
        print(f"\nMin {i} over\n")

    print('Main over')


if __name__ == '__main__':
    loop = asyncio.get_event_loop()
    loop.run_until_complete(main())
