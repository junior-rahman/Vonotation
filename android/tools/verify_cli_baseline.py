#!/usr/bin/env python3
"""Verify the fixed library graph and merged native resources against the saved APK.
Compares decoded manifest declarations (except requested version metadata), all
non-app resource values and payloads, and published library package metadata.
No baseline classes or resources are used as build inputs.
"""
import argparse, hashlib, io, json, os, re, subprocess, zipfile
from pathlib import Path
from PIL import Image
parser=argparse.ArgumentParser();parser.add_argument('--baseline',required=True);parser.add_argument('--candidate',required=True);parser.add_argument('--report',required=True);args=parser.parse_args()
aapt=Path(os.environ['ANDROID_SDK_ROOT'])/'build-tools/35.0.0/aapt2'
def cmd(*c):return subprocess.check_output([str(aapt),*map(str,c)],text=True)
def resources(apk):
    raw=cmd('dump','resources',apk);names={};entries={};current=None
    for line in raw.splitlines():
        m=re.match(r'\s*resource (0x[0-9a-f]+) (\S+)',line)
        if m:current=m[2];names[m[1]]=current;entries[current]=[]
        elif re.match(r'\s*type \w+',line):current=None
        elif current:entries[current].append(line.strip())
    return raw,names,entries
oldraw,oldnames,oldentries=resources(args.baseline);newraw,newnames,newentries=resources(args.candidate)
def refs(value,names):return re.sub(r'0x7f[0-9a-f]{6}',lambda m:'RESOURCE('+names.get(m[0],m[0])+')',value)
def xml(apk,path,names):
    raw=cmd('dump','xmltree',apk,'--file',path);roots=[];stack=[]
    for line in raw.splitlines():
        indent=len(line)-len(line.lstrip());text=line.strip()
        if text.startswith('E: '):
            tag=re.sub(r' \(line=\d+\)$','',text[3:]);node=[tag,[],[]]
            while stack and stack[-1][0]>=indent:stack.pop()
            (stack[-1][1][2] if stack else roots).append(node);stack.append((indent,node))
        elif text.startswith('A: ') and stack:
            attr=refs(text[3:],names)
            # Requested version differs; all other SDK/application/library declarations must match.
            if stack[-1][1][0]=='manifest' and re.match(r'http://schemas.android.com/apk/res/android:version(Code|Name)',attr):continue
            stack[-1][1][1].append(attr)
        elif text.startswith('C: ') and stack:stack[-1][1][1].append(text)
    def canonical(n):
        children=[canonical(c) for c in n[2]]
        if path=='AndroidManifest.xml':children=sorted(children,key=lambda v:json.dumps(v))
        return [n[0],sorted(n[1]),children]
    return sorted([canonical(n) for n in roots],key=lambda v:json.dumps(v))
manifest_same=xml(args.baseline,'AndroidManifest.xml',oldnames)==xml(args.candidate,'AndroidManifest.xml',newnames)
app={'style/AppTheme','style/BaseAppTheme','style/CaptureTheme','drawable/ic_launcher','string/accessibility_description','xml/accessibility','xml/keyboard'}
oldzip=zipfile.ZipFile(args.baseline);newzip=zipfile.ZipFile(args.candidate)
errors=[];equal=0;filechecks=0;app_changes={}
def normal(lines,names):
    values=[refs(re.sub(r'\(file\) \S+', '(file) RESOURCE_FILE',l),names) for l in lines]
    # Enum dictionaries are unordered name/value mappings; retain ordered arrays/styles.
    if values and '(attr)' in values[0]:return values[:1]+sorted(values[1:])
    return values
for name in sorted(oldentries.keys()|newentries.keys()):
    old=oldentries.get(name);new=newentries.get(name)
    if name in app:
        if old is None or new is None:errors.append({'resource':name,'reason':'App resource missing'})
        elif normal(old,oldnames)!=normal(new,newnames):app_changes[name]={'before':old,'after':new}
        continue
    if old is None or new is None:errors.append({'resource':name,'reason':'Resource name missing','baseline':old,'candidate':new});continue
    if normal(old,oldnames)!=normal(new,newnames):errors.append({'resource':name,'reason':'Decoded resource value mismatch','baseline':normal(old,oldnames),'candidate':normal(new,newnames)});continue
    equal+=1
    oldfiles=[re.search(r'\(file\) (\S+) type=(\w+)',s).groups() for s in old if '(file)' in s]
    newfiles=[re.search(r'\(file\) (\S+) type=(\w+)',s).groups() for s in new if '(file)' in s]
    for (op,ot),(np,nt) in zip(oldfiles,newfiles):
        filechecks+=1
        if ot!=nt:errors.append({'resource':name,'reason':'Resource payload type mismatch'});continue
        if ot=='XML':same=xml(args.baseline,op,oldnames)==xml(args.candidate,np,newnames)
        else:
            ob,nb=oldzip.read(op),newzip.read(np)
            if ob==nb:same=True
            else:
                try:
                    oi,ni=Image.open(io.BytesIO(ob)).convert('RGBA'),Image.open(io.BytesIO(nb)).convert('RGBA');same=oi.size==ni.size and oi.tobytes()==ni.tobytes()
                except Exception:same=False
        if not same:errors.append({'resource':name,'reason':'Decoded resource payload mismatch','baselineFile':op,'candidateFile':np})
metadata=[n for n in oldzip.namelist() if n.endswith('.version') or n.endswith('play-services-auth.properties') or n.endswith('play-services-base.properties') or n.endswith('play-services-basement.properties') or n.endswith('play-services-tasks.properties') or n.endswith('play-services-fido.properties') or n.endswith('play-services-auth-base.properties') or n.endswith('play-services-auth-api-phone.properties')]
missing_metadata=[n for n in metadata if n not in newzip.namelist() or oldzip.read(n)!=newzip.read(n)]
# Compare Java service registrations + runtime Kotlin builtins separately from APK code/signing.
java_resources=[n for n in oldzip.namelist() if n.startswith(('kotlin/','META-INF/services/')) or n=='DebugProbesKt.bin']
missing_java=[n for n in java_resources if n not in newzip.namelist() or oldzip.read(n)!=newzip.read(n)]
result={'baselineSha256':hashlib.sha256(Path(args.baseline).read_bytes()).hexdigest(),'candidateSha256':hashlib.sha256(Path(args.candidate).read_bytes()).hexdigest(),'manifestDeclarationsEqualExceptRequestedVersion':manifest_same,'baselineResourceNames':len(oldentries),'candidateResourceNames':len(newentries),'nonAppResourceValuesComparedEqual':equal,'resourcePayloadsChecked':filechecks,'resourceMismatches':errors,'appResourceValueChanges':app_changes,'publishedLibraryMetadataCompared':len(metadata),'libraryMetadataMismatches':missing_metadata,'runtimeJavaResourcesCompared':len(java_resources),'runtimeJavaResourceMismatches':missing_java,'baselineProfileAssetsNote':'Baseline APK contains AGP-generated dexopt profiles. CLI build does not reuse old address-specific profiles; no functional declarations/resources removed.'}
result['pass']=manifest_same and not errors and not missing_metadata and not missing_java
Path(args.report).parent.mkdir(parents=True,exist_ok=True);Path(args.report).write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in ('resourceMismatches','appResourceValueChanges')},indent=2));print('Resource mismatches:',len(errors))
if not result['pass']:raise SystemExit(1)
