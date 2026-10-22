#!/usr/bin/env python3
"""Read ONLY the installed debug app's contacts DB via authorized USB debugging.
No uninstall/install, credential/session export, root, or writes to phone storage.
"""
import argparse,io,json,pathlib,shlex,sqlite3,subprocess,tarfile,tempfile
PACKAGE='ir.electricconnect.tdlibpersonal'
DB='electric_connect_native_free.db'
def convert_database(path):
    with sqlite3.connect(path) as db:
        db.row_factory=sqlite3.Row
        if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok':
            raise RuntimeError('SQLite backup integrity check failed')
        records=[]
        for row in db.execute('SELECT * FROM records'):
            row=dict(row)
            records.append({k:str(row.get(k,default)) for k,default in [
                ('title',''),('phone',''),('category',''),('source',''),
                ('provider','قدیمی / دستی'),('retrieved',0),('created',0)]})
        drafts=[{k:str(row[k]) for k in ('destination','message','created')}
                for row in db.execute('SELECT destination,message,created FROM drafts')]
        return {'format':'electric-connect-local-v1','records':records,'drafts':drafts,'groups':[]}
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=pathlib.Path)
    parser.add_argument('--database',type=pathlib.Path,help='Convert an already safely copied database instead of using adb')
    args=parser.parse_args()
    if args.output.exists(): raise SystemExit('Output exists; choose a new filename')
    if args.database:
        backup=convert_database(args.database)
    else:
        subprocess.run(['adb','shell','am','force-stop',PACKAGE],check=True)
        command=f'cd databases && tar -cf - {DB}*'
        result=subprocess.run(['adb','exec-out','run-as',PACKAGE,'sh','-c',shlex.quote(command)],check=True,stdout=subprocess.PIPE)
        with tempfile.TemporaryDirectory() as temp:
            with tarfile.open(fileobj=io.BytesIO(result.stdout)) as archive:
                for member in archive.getmembers():
                    if member.name not in (DB,DB+'-wal',DB+'-shm') or not member.isfile():
                        raise RuntimeError('Unexpected backup member')
                    pathlib.Path(temp,member.name).write_bytes(archive.extractfile(member).read())
            backup=convert_database(pathlib.Path(temp,DB))
    args.output.write_text(json.dumps(backup,ensure_ascii=False),encoding='utf-8')
    args.output.chmod(0o600)
    # Verify file can be read back and counts agree before reporting success.
    verified=json.loads(args.output.read_text(encoding='utf-8'))
    assert len(verified['records'])==len(backup['records'])
    print(f"Validated backup: {len(backup['records'])} contacts, {len(backup['drafts'])} drafts. Phone app was retained.")
if __name__=='__main__':main()
