#!/usr/bin/env python3
"""Strip/copy the owned Android runtime, regenerate its license and manifest."""
import argparse,hashlib,json,os,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('build_root',type=Path);p.add_argument('--ndk',type=Path,default=os.environ.get('ANDROID_NDK_HOME'));a=p.parse_args()
if not a.ndk:p.error('set ANDROID_NDK_HOME or --ndk')
r=Path(__file__).resolve().parents[2];src=a.build_root/'llama.cpp-omni';commit='e0eca9d8f57ac8f5a5ad6191a8007b93436c1e71'
assert subprocess.check_output(['git','-C',str(src),'rev-parse','HEAD'],text=True).strip()==commit
binary=a.build_root/'build-android/bin/libminicpm-server.so'
llvm=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
dest=r/'android/app/src/main/jniLibs/arm64-v8a/libminicpm-server.so';dest.parent.mkdir(parents=True,exist_ok=True)
staged=dest.with_suffix('.so.tmp');shutil.copy2(binary,staged)
subprocess.run([str(llvm/'llvm-strip'),'--strip-unneeded',str(staged)],check=True)
dyn=subprocess.check_output([str(llvm/'llvm-readelf'),'-d',str(staged)],text=True)
needed=[l.split('[')[1].split(']')[0] for l in dyn.splitlines() if '(NEEDED)' in l]
assert sorted(needed)==['libc.so','libdl.so','libm.so'],needed
ph=subprocess.check_output([str(llvm/'llvm-readelf'),'-l',str(staged)],text=True)
loads=[l for l in ph.splitlines() if l.strip().startswith('LOAD')]
assert loads and all(l.endswith('0x4000') for l in loads),'Expected 16KiB ELF segments'
staged.replace(dest)
license=r/'assets/licenses/minicpm-native.txt';license.parent.mkdir(parents=True,exist_ok=True)
license.write_text('MiniCPM ServLlama CPU HTTP runtime\nSource: https://github.com/tc-mb/llama.cpp-omni\nRevision: '+commit+'\nLocally modified; see patches/minicpm/servllama-http.patch and native/minicpm.\n\n'+'\n\n'.join('===== '+f+' =====\n'+(src/f).read_text() for f in ['LICENSE','licenses/LICENSE-httplib','licenses/LICENSE-jsonhpp','licenses/LICENSE-linenoise']))
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
s={'schemaVersion':1,'version':'e0eca9d8-servllama-1','variant':'cpu','source':{'repository':'https://github.com/tc-mb/llama.cpp-omni','commit':commit,'patch':'patches/minicpm/servllama-http.patch','modified':True},'abi':'arm64-v8a','minSdk':28,'backends':['cpu'],'externalDependencies':['libc.so','libm.so','libdl.so'],'license':{'asset':'assets/licenses/minicpm-native.txt','sha256':sha(license)},'libraries':[{'name':dest.name,'sha256':sha(dest),'bytes':dest.stat().st_size}],'build':{'ndk':'27.0.12077973','androidApi':28,'pageAlignment':16384,'internalLibraries':'static','cppRuntime':'static','command':'native/minicpm/build.sh android'}}
manifest=r/'assets/bin/minicpm_server_manifest.json';manifest.parent.mkdir(parents=True,exist_ok=True);manifest.write_text(json.dumps(s,indent=2)+'\n')
print(f'Packaged {dest.name}: {dest.stat().st_size} bytes, SHA256 {sha(dest)}')
