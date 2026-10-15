#!/usr/bin/env python3
"""Resolve the fixed public Google Auth dependency graph from official Maven repositories.
Not a general Gradle replacement. Rejects unresolved/dynamic versions and verifies the
fixed graph against the preserved baseline metadata when --baseline is supplied.
"""
import argparse, concurrent.futures, hashlib, json, os, re, subprocess, threading, xml.etree.ElementTree as ET
from pathlib import Path

NS={'m':'http://maven.apache.org/POM/4.0.0'}
GOOGLE='https://dl.google.com/dl/android/maven2/'
CENTRAL='https://repo.maven.apache.org/maven2/'
parser=argparse.ArgumentParser()
parser.add_argument('--cache',required=True)
parser.add_argument('--lock',required=True)
parser.add_argument('--root',default='com.google.android.gms:play-services-auth:22.0.0')
args=parser.parse_args()
cache=Path(args.cache); cache.mkdir(parents=True,exist_ok=True)
fetchlock=threading.Lock(); fetchlocks={}; fetched={}

def fetch(g,a,v,extension):
    rel=f'{g.replace(".","/")}/{a}/{v}/{a}-{v}.{extension}'
    dest=cache/rel; dest.parent.mkdir(parents=True,exist_ok=True)
    repo=GOOGLE if g.startswith(('com.google.android','androidx.')) or g.startswith('com.android.') else CENTRAL
    with fetchlock: pathlock=fetchlocks.setdefault(str(dest),threading.Lock())
    with pathlock:
        if not dest.exists():
            tmp=dest.with_suffix(dest.suffix+'.tmp')
            subprocess.run(['curl','--fail','--location','--silent','--show-error','--retry','2','--connect-timeout','20','--max-time','120',repo+rel,'-o',str(tmp)],check=True)
            tmp.replace(dest)
    fetched[str(dest)]={'url':repo+rel,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}
    return dest

models={}
def model(g,a,v):
    key=(g,a,v)
    if key in models:return models[key]
    root=ET.parse(fetch(g,a,v,'pom')).getroot()
    parent=root.find('m:parent',NS)
    props={}; managed={}
    if parent is not None:
        pcoords=tuple(parent.findtext('m:'+n,namespaces=NS) for n in ('groupId','artifactId','version'))
        pm=model(*pcoords); props.update(pm['props']);managed.update(pm['managed'])
    props.update({'project.groupId':g,'project.artifactId':a,'project.version':v,'pom.groupId':g,'pom.artifactId':a,'pom.version':v})
    node=root.find('m:properties',NS)
    if node is not None:props.update({e.tag.split('}')[-1]:e.text or '' for e in node})
    def expand(t):
        for _ in range(10):
            nt=re.sub(r'\$\{([^}]+)\}',lambda m:props.get(m.group(1),m.group()),t or '')
            if nt==t:return nt
            t=nt
        return t
    def dep(e):
        d={n:expand(e.findtext('m:'+n,namespaces=NS)) for n in ('groupId','artifactId','version','scope','type','optional')}
        k=(d['groupId'],d['artifactId'])
        if not d['version']:d['version']=managed.get(k,'')
        d['exclusions']=[(expand(x.findtext('m:groupId',namespaces=NS)),expand(x.findtext('m:artifactId',namespaces=NS))) for x in e.findall('m:exclusions/m:exclusion',NS)]
        return d
    for e in root.findall('m:dependencyManagement/m:dependencies/m:dependency',NS):
        d=dep(e)
        if d['scope']=='import' and d['type']=='pom':managed.update(model(d['groupId'],d['artifactId'],d['version'])['managed'])
        else:managed[(d['groupId'],d['artifactId'])]=d['version']
    result={'props':props,'managed':managed,'packaging':root.findtext('m:packaging',default='jar',namespaces=NS),'deps':[dep(e) for e in root.findall('m:dependencies/m:dependency',NS)]}
    models[key]=result; return result

def fixed(v):
    if re.fullmatch(r'\[[^,]+\]',v):return v[1:-1]
    if not re.fullmatch(r'[0-9][A-Za-z0-9_.-]*',v):raise ValueError('Unresolved/dynamic Maven version: '+v)
    return v

def numeric(v):return tuple(int(p) if p.isdigit() else p for p in re.split(r'[.-]',v))

root_group,root_artifact,root_version=args.root.split(':')
root_key=(root_group,root_artifact)
selected={root_key:fixed(root_version)}
# POM publication of kotlinx-coroutines-core is a multiplatform metadata artifact.
# Its official JVM publication is the same-version core-jvm artifact used by Android Gradle.
# Kotlin stdlib artifacts version mediation follows highest selected fixed version.
for iteration in range(30):
    before=dict(selected)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        resolved=list(pool.map(lambda kv:(kv,model(*kv[0],kv[1])), list(selected.items())))
    print(f'Graph pass {iteration}: {len(selected)} selected modules',flush=True)
    for ((g,a),v), module in resolved:
        for d in module['deps']:
            if d['scope'] in ('test','provided','system') or d['optional']=='true':continue
            dg,da=d['groupId'],d['artifactId']
            if da=='kotlinx-coroutines-core' and dg=='org.jetbrains.kotlinx':da='kotlinx-coroutines-core-jvm'
            dv=fixed(d['version']); key=(dg,da)
            if key not in selected or numeric(dv)>numeric(selected[key]):selected[key]=dv
    if before==selected:break
else:raise ValueError('Dependency graph did not converge')

# Traverse only the final selected graph, dropping dependencies reached only by displaced versions.
reachable=set()
def walk(key):
    if key in reachable:return
    reachable.add(key)
    for d in model(*key,selected[key])['deps']:
        if d['scope'] in ('test','provided','system') or d['optional']=='true':continue
        k=(d['groupId'],'kotlinx-coroutines-core-jvm' if d['groupId']=='org.jetbrains.kotlinx' and d['artifactId']=='kotlinx-coroutines-core' else d['artifactId'])
        walk(k)
walk(root_key)
selected={k:v for k,v in selected.items() if k in reachable}

def artifact(kv):
    (g,a),v=kv; m=model(g,a,v); typ=m['packaging']
    if typ not in ('jar','aar'):raise ValueError(f'Unexpected artifact packaging: {g}:{a}:{v} {typ}')
    path=fetch(g,a,v,typ)
    return {'group':g,'artifact':a,'version':v,'type':typ,**fetched[str(path)]}
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:artifacts=list(pool.map(artifact,sorted(selected.items())))
lock={'root':args.root,'repositories':[GOOGLE,CENTRAL],'resolver':'fixed-version POM graph with Android/JVM coroutines variant; numeric highest-version mediation','artifacts':artifacts,'metadata':list(fetched.values())}
Path(args.lock).parent.mkdir(parents=True,exist_ok=True);Path(args.lock).write_text(json.dumps(lock,indent=2)+'\n')
print(f'Resolved {len(artifacts)} public AAR/JAR artifacts; lock saved at {args.lock}',flush=True)
