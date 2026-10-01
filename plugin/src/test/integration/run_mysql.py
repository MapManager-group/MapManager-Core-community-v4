#!/usr/bin/env python3
"""使用已有 MySQL 二进制启动临时数据库并验证存储；不安装或连接现有数据库。"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mysql-base', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True, help='提供 Bukkit libraries 的 Paper 目录')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--port', type=int, default=13376)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    work = Path(tempfile.mkdtemp(prefix='mmc-mysql-'))
    base = args.mysql_base.resolve()
    data = work / 'database'
    socket = work / 'mysql.sock'
    java = args.java_home.resolve() / 'bin'
    print(f'MySQL test directory: {work}', flush=True)
    subprocess.run([str(base / 'bin/mysqld'), '--no-defaults', '--initialize-insecure',
                    f'--basedir={base}', f'--datadir={data}'], check=True)
    process = subprocess.Popen([str(base / 'bin/mysqld'), '--no-defaults', f'--basedir={base}',
                               f'--datadir={data}', '--bind-address=127.0.0.1', f'--port={args.port}',
                               '--mysqlx=OFF', f'--socket={socket}', f'--pid-file={work / "mysql.pid"}',
                               f'--log-error={work / "mysql.log"}', '--innodb-buffer-pool-size=64M'])
    admin = [str(base / 'bin/mysqladmin'), '--no-defaults', f'--socket={socket}', '-u', 'root']
    try:
        deadline = time.monotonic() + 30
        while not socket.exists():
            if process.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError(f'MySQL failed to start; see {work / "mysql.log"}')
            time.sleep(0.2)
        subprocess.run(admin + ['ping'], check=True)
        classes = work / 'classes'
        classes.mkdir()
        jars = [repo / 'plugin/target/mapmanager-plugin-4.0.0-SNAPSHOT.jar'] + list((args.runtime.resolve() / 'libraries').rglob('*.jar'))
        cp = os.pathsep.join(map(str, jars))
        subprocess.run([str(java / 'javac'), '-proc:none', '-cp', cp, '-d', str(classes),
                        str(Path(__file__).with_name('StorageSqlProbe.java'))], check=True)
        subprocess.run([str(java / 'java'), '-cp', str(classes) + os.pathsep + cp, 'mmc.probe.StorageSqlProbe',
                        str(args.port), 'mmctodo_' + uuid.uuid4().hex, str(work / 'snapshot')], check=True)
    finally:
        if process.poll() is None:
            subprocess.run(admin + ['shutdown'], check=False, timeout=15)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=15)


if __name__ == '__main__':
    main()
