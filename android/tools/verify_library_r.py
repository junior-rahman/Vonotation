#!/usr/bin/env python3
"""Independent DEX check: library R fields/values/linked styleable arrays match baseline.
This reader only verifies. It never supplies any baseline code or data to the build.
"""
import argparse,hashlib,json,struct,zipfile,re
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--baseline',required=True);parser.add_argument('--candidate',required=True);parser.add_argument('--report',required=True);args=parser.parse_args()
def read(apk):
 result={};descriptors=set()
 with zipfile.ZipFile(apk) as z:
  for filename in z.namelist():
   if not re.fullmatch(r'classes\d*\.dex',filename):continue
   b=z.read(filename);sc,so,tc,to=struct.unpack_from('<4I',b,56);fc,fo,mc,mo=struct.unpack_from('<4I',b,80)
   def u(p):
    val=0;shift=0
    while True:
     byte=b[p];p+=1;val|=(byte&127)<<shift;shift+=7
     if not byte&128:return val,p
   def string(i):
    p=struct.unpack_from('<I',b,so+4*i)[0];_,p=u(p);return b[p:b.index(0,p)].decode('utf8')
   types=[string(struct.unpack_from('<I',b,to+4*i)[0]) for i in range(tc)]
   fields=[(types[c],string(n)) for c,ty,n in (struct.unpack_from('<HHI',b,fo+8*i) for i in range(fc))]
   methods=[(types[c],string(n)) for c,proto,n in (struct.unpack_from('<HHI',b,mo+8*i) for i in range(mc))]
   cc,co=struct.unpack_from('<2I',b,96)
   for i in range(cc):
    cd=struct.unpack_from('<8I',b,co+32*i);name=types[cd[0]];descriptors.add(name)
    if not re.fullmatch(r'L(?:androidx|com/google/android/gms)/.*/R(?:\$\w+)?;',name):continue
    data={};p=cd[6]
    if not p:result[name]=data;continue
    counts=[]
    for _ in range(4):v,p=u(p);counts.append(v)
    static=[];idx=0
    for _ in range(counts[0]):diff,p=u(p);access,p=u(p);idx+=diff;static.append(fields[idx][1]);data[fields[idx][1]]=0
    for _ in range(counts[1]):_,p=u(p);_,p=u(p)
    direct=[];idx=0
    for _ in range(counts[2]):diff,p=u(p);access,p=u(p);code,p=u(p);idx+=diff;direct.append((methods[idx],code))
    pvals=cd[7]
    if pvals:
     count,pvals=u(pvals)
     for j in range(count):
      head=b[pvals];pvals+=1;kind=head&31;length=(head>>5)+1
      if kind in (0x1e,0x1f):v=None if kind==0x1e else head>>5
      elif kind==4:v=int.from_bytes(b[pvals:pvals+length],'little',signed=True)&0xffffffff;pvals+=length
      else:raise ValueError('Unexpected R static value kind '+str(kind))
      data[static[j]]=v
    for method,code in direct:
     if method[1]!='<clinit>' or not code:continue
     count=struct.unpack_from('<I',b,code+12)[0];words=list(struct.unpack_from('<'+str(count)+'H',b,code+16));pc=0;regs={};last=None
     while pc<count:
      word=words[pc];op=word&255
      if op==0x0e:break
      if op==0x12:
       lit=(word>>12)&15;regs[(word>>8)&15]=lit if lit<8 else lit-16;pc+=1
      elif op in (0x13,0x14,0x15):
       reg=word>>8
       if op==0x13:val=words[pc+1];val=val if val<32768 else val-65536;width=2
       elif op==0x14:val=words[pc+1]|words[pc+2]<<16;width=3
       else:val=words[pc+1]<<16;width=2
       regs[reg]=val;pc+=width
      elif op==0x23:regs[(word>>8)&15]=[None]*regs[(word>>12)&15];pc+=2
      elif op==0x26:
       off=words[pc+1]|words[pc+2]<<16;off=off if off<2**31 else off-2**32;at=pc+off
       if words[at]!=0x0300 or words[at+1]!=4:raise ValueError('Unknown R array data encoding')
       n=words[at+2]|words[at+3]<<16;regs[word>>8]=[words[at+4+2*j]|words[at+5+2*j]<<16 for j in range(n)];pc+=3
      elif op==0x24:
       n=word>>12;packed=words[pc+2];r=[(packed>>(4*j))&15 for j in range(4)]+[(word>>8)&15];last=[regs[k] for k in r[:n]];pc+=3
      elif op==0x0c:regs[word>>8]=last;pc+=1
      elif op==0x4b:
       value=regs[word>>8];packed=words[pc+1];regs[packed&255][regs[packed>>8]]=value;pc+=2
      elif op in (0x67,0x69):
       own,field=fields[words[pc+1]]
       if own!=name:raise ValueError('Cross-class assignment in R init')
       data[field]=regs[word>>8];pc+=2
      elif op==0x00:pc+=1
      else:raise ValueError('Unknown R clinit opcode '+hex(op)+' in '+name)
    result[name]=data
 return result,descriptors
baseline,oldclasses=read(args.baseline);candidate,newclasses=read(args.candidate)
# IDs may legitimately differ. Resource name normalization uses the corresponding
# freshly decoded table rather than assuming copied numerical assignments.
import os,subprocess
exe=Path(os.environ['ANDROID_SDK_ROOT'])/'build-tools/35.0.0/aapt2'
def names(apk):
 raw=subprocess.check_output([str(exe),'dump','resources',apk],text=True)
 return {int(i,16):name for i,name in re.findall(r'resource (0x[0-9a-f]+) (\S+)',raw)}
on,nn=names(args.baseline),names(args.candidate)
def norm(v,m):
 if isinstance(v,list):return [norm(x,m) for x in v]
 return 'RESOURCE('+m[v]+')' if isinstance(v,int) and v in m else v
errors=[];fields=0;arrays=0
for cls,values in baseline.items():
 if cls not in candidate:errors.append({'class':cls,'reason':'Class missing'});continue
 for field,v in values.items():
  fields+=1;arrays+=isinstance(v,list)
  if field not in candidate[cls] or norm(v,on)!=norm(candidate[cls][field],nn):errors.append({'class':cls,'field':field,'baseline':norm(v,on),'candidate':norm(candidate[cls].get(field),nn)})
 # Extra fields must be reported as differences, not silently accepted.
 for field in candidate[cls].keys()-values.keys():errors.append({'class':cls,'field':field,'reason':'Extra R field'})
extra_classes=sorted(candidate.keys()-baseline.keys())
# Empty R wrappers for manifest-only libraries have no runtime fields.
nonempty_extra=[cls for cls in extra_classes if candidate[cls]]
for cls in nonempty_extra:errors.append({'class':cls,'reason':'Extra nonempty library R class'})
missing_nonapp=sorted(c for c in oldclasses-newclasses if not c.startswith('Lapp/voiceflow/') and '$$ExternalSynthetic' not in c)
result={'baselineSha256':hashlib.sha256(Path(args.baseline).read_bytes()).hexdigest(),'candidateSha256':hashlib.sha256(Path(args.candidate).read_bytes()).hexdigest(),'baselineLibraryRClasses':len(baseline),'candidateLibraryRClasses':len(candidate),'scalarOrArrayFieldsCompared':fields,'styleableArraysCompared':arrays,'emptyExtraRWrappers':extra_classes,'rMismatches':errors,'missingNonAppNonSyntheticClasses':missing_nonapp,'pass':not errors and not missing_nonapp,'note':'Compiler-generated synthetic lambda classes may differ between D8 versions; empty manifest-only R wrappers carry no fields.'}
Path(args.report).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if not result['pass']:raise SystemExit(1)
