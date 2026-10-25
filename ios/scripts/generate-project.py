#!/usr/bin/env python3
"""Generate the checked-in dependency-free Xcode project deterministically."""
import argparse
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "VonoTation.xcodeproj"

def uid(value):
    return hashlib.sha256(value.encode()).hexdigest()[:24].upper()

def quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'

def render():
    objects = {}
    def obj(key, body):
        objects[key] = f"\t\t{uid(key)} = {{ {body} }};"
    def ids(keys):
        return "(" + ", ".join(uid(x) for x in keys) + ");"
    targets = ("VonoTation", "VonoTationTests", "VonoTationUITests", "VonoNotes", "VonoNotesTests", "VonoNotesUITests")
    for target in targets:
        is_app = target in ("VonoTation", "VonoNotes")
        host = "VonoNotes" if target.startswith("VonoNotes") else "VonoTation"
        source_folder = "VonoTation" if is_app else target
        source_keys = []
        child_keys = []
        for path in sorted((ROOT / source_folder).rglob("*.swift")):
            rel = path.relative_to(ROOT).as_posix()
            fkey, bkey = f"file:{rel}", f"build:{target}:{rel}"
            obj(fkey, f"isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {quote(rel)}; sourceTree = SOURCE_ROOT;")
            obj(bkey, f"isa = PBXBuildFile; fileRef = {uid(fkey)};")
            child_keys.append(fkey)
            source_keys.append(bkey)
        resources = []
        if is_app:
            for path in sorted((ROOT / "VonoTation").rglob("*.xcassets")):
                rel = path.relative_to(ROOT).as_posix()
                fkey, bkey = f"file:{rel}", f"build:{target}:{rel}"
                obj(fkey, f"isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = {quote(rel)}; sourceTree = SOURCE_ROOT;")
                obj(bkey, f"isa = PBXBuildFile; fileRef = {uid(fkey)};")
                child_keys.append(fkey)
                resources.append(bkey)
            obj("file:Info", 'isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = "VonoTation/Info.plist"; sourceTree = SOURCE_ROOT;')
            child_keys.append("file:Info")
        # Both apps compile the same file references, which must have a single
        # navigator group parent. Target membership lives in the build phases.
        if target != "VonoNotes":
            obj(f"group:{target}", f"isa = PBXGroup; name = {quote(target)}; children = {ids(child_keys)} sourceTree = {quote('<group>')};")
        obj(f"sources:{target}", f"isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {ids(source_keys)} runOnlyForDeploymentPostprocessing = 0;")
        obj(f"resources:{target}", f"isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {ids(resources)} runOnlyForDeploymentPostprocessing = 0;")
        obj(f"frameworks:{target}", "isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;")
        ext = "app" if is_app else "xctest"
        product_type = "application" if is_app else ("bundle.unit-test" if target.endswith("Tests") and not target.endswith("UITests") else "bundle.ui-testing")
        obj(f"product:{target}", f"isa = PBXFileReference; explicitFileType = {quote('wrapper.application' if is_app else 'wrapper.cfbundle')}; includeInIndex = 0; path = {quote(target + '.' + ext)}; sourceTree = BUILT_PRODUCTS_DIR;")
        deps = []
        if not is_app:
            obj(f"proxy:{target}", f"isa = PBXContainerItemProxy; containerPortal = {uid('project')}; proxyType = 1; remoteGlobalIDString = {uid('target:' + host)}; remoteInfo = {host};")
            obj(f"dependency:{target}", f"isa = PBXTargetDependency; target = {uid('target:' + host)}; targetProxy = {uid('proxy:' + target)};")
            deps = [f"dependency:{target}"]
        obj(f"target:{target}", f"isa = PBXNativeTarget; buildConfigurationList = {uid('configlist:' + target)}; buildPhases = {ids([f'sources:{target}', f'frameworks:{target}', f'resources:{target}'])} buildRules = (); dependencies = {ids(deps)} name = {quote(target)}; productName = {quote(target)}; productReference = {uid('product:' + target)}; productType = {quote('com.apple.product-type.' + product_type)};")
        for config in ("Debug", "Release"):
            settings = {
                "PRODUCT_NAME": "$(TARGET_NAME)", "SWIFT_VERSION": "5.0",
                "IPHONEOS_DEPLOYMENT_TARGET": "17.0", "TARGETED_DEVICE_FAMILY": "1,2",
                "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "SDKROOT": "iphoneos",
                "CODE_SIGN_STYLE": "Automatic", "DEVELOPMENT_TEAM": "",
                "CURRENT_PROJECT_VERSION": "1" if host == "VonoNotes" else "90", "MARKETING_VERSION": "0.1.0" if host == "VonoNotes" else "0.1.18",
                "SWIFT_STRICT_CONCURRENCY": "minimal", "ENABLE_USER_SCRIPT_SANDBOXING": "YES",
                "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks",
                "PRODUCT_BUNDLE_IDENTIFIER": ("app.vonotation.notes.ios" if host == "VonoNotes" else "app.vonotation.ios") + ("" if is_app else "." + target),
                "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if config == "Debug" else "-O",
                "SWIFT_COMPILATION_MODE": "singlefile" if config == "Debug" else "wholemodule",
                "DEBUG_INFORMATION_FORMAT": "dwarf" if config == "Debug" else "dwarf-with-dsym",
            }
            if config == "Debug":
                settings.update({"SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG $(inherited)", "ENABLE_TESTABILITY": "YES"})
            if is_app:
                settings["VONO_DISPLAY_NAME"] = "Vocading" if host == "VonoNotes" else "Voitation"
                settings["VONO_URL_SCHEME"] = "vononotes" if host == "VonoNotes" else "vonotation"
                settings["VONO_BACKUP_IDENTIFIER"] = "app.vonotation.notes.backup" if host == "VonoNotes" else "app.vonotation.backup"
                settings["VONO_MICROPHONE_REASON"] = "Record voice notes when you choose Record. Call audio is imported from Files." if host == "VonoNotes" else "Record dictations and Quick Thoughts when you choose Record."
                if host == "VonoNotes": settings["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] = ("DEBUG " if config == "Debug" else "") + "VONO_NOTES $(inherited)"
                settings.update({"INFOPLIST_FILE": "VonoTation/Info.plist", "GENERATE_INFOPLIST_FILE": "NO", "SUPPORTS_MACCATALYST": "NO"})
                if (ROOT / "VonoTation/Assets.xcassets/AppIcon.appiconset").is_dir():
                    settings["ASSETCATALOG_COMPILER_APPICON_NAME"] = "AppIcon"
            else:
                settings.update({"GENERATE_INFOPLIST_FILE": "YES", "SKIP_INSTALL": "YES"})
                if target.endswith("Tests") and not target.endswith("UITests"):
                    settings.update({"TEST_HOST": f"$(BUILT_PRODUCTS_DIR)/{host}.app/{host}", "BUNDLE_LOADER": "$(TEST_HOST)"})
                else:
                    settings["TEST_TARGET_NAME"] = host
            body = " ".join(f"{k} = {quote(v)};" for k, v in settings.items())
            obj(f"config:{target}:{config}", f"isa = XCBuildConfiguration; buildSettings = {{ {body} }}; name = {config};")
        obj(f"configlist:{target}", f"isa = XCConfigurationList; buildConfigurations = {ids([f'config:{target}:Debug', f'config:{target}:Release'])} defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;")
    obj("products", f"isa = PBXGroup; children = {ids(['product:' + x for x in targets])} name = Products; sourceTree = {quote('<group>')};")
    obj("group:main", f"isa = PBXGroup; children = {ids(['group:' + x for x in targets if x != 'VonoNotes'] + ['products'])} sourceTree = {quote('<group>')};")
    for config in ("Debug", "Release"):
        obj("project-config:" + config, f"isa = XCBuildConfiguration; buildSettings = {{ CLANG_ENABLE_MODULES = YES; CLANG_ENABLE_OBJC_ARC = YES; GCC_C_LANGUAGE_STANDARD = gnu17; GCC_WARN_INHIBIT_ALL_WARNINGS = NO; SWIFT_VERSION = 5.0; }}; name = {config};")
    obj("project-configlist", f"isa = XCConfigurationList; buildConfigurations = {ids(['project-config:Debug', 'project-config:Release'])} defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;")
    target_attributes = " ".join(f"{uid('target:' + target)} = {{ TestTargetID = {uid('target:' + ('VonoNotes' if target.startswith('VonoNotes') else 'VonoTation'))}; }};" for target in targets if target not in ("VonoTation", "VonoNotes"))
    obj("project", f"isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastSwiftUpdateCheck = 1600; LastUpgradeCheck = 1600; TargetAttributes = {{ {target_attributes} }}; }}; buildConfigurationList = {uid('project-configlist')}; compatibilityVersion = {quote('Xcode 14.0')}; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base); mainGroup = {uid('group:main')}; productRefGroup = {uid('products')}; projectDirPath = {quote('')}; projectRoot = {quote('')}; targets = {ids(['target:' + x for x in targets])}")
    return '// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n' + '\n'.join(objects.values()) + f'\n\t}};\n\trootObject = {uid("project")};\n}}\n'

def scheme(application="VonoTation"):
    def buildable(target):
        ext = "app" if target in ("VonoTation", "VonoNotes") else "xctest"
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid("target:" + target)}" BuildableName="{target}.{ext}" BlueprintName="{target}" ReferencedContainer="container:VonoTation.xcodeproj"/>'
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
    <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{buildable(application)}</BuildActionEntry>
  </BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="NO">
    <Testables><TestableReference skipped="NO" parallelizable="NO">{buildable(application + "Tests")}</TestableReference><TestableReference skipped="NO" parallelizable="NO">{buildable(application + "UITests")}</TestableReference></Testables>
    <MacroExpansion>{buildable(application)}</MacroExpansion>
  </TestAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{buildable(application)}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugServiceExtension="internal"><BuildableProductRunnable runnableDebuggingMode="0">{buildable(application)}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
'''

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = {PROJECT / "project.pbxproj": render(), PROJECT / "xcshareddata/xcschemes/VonoTation.xcscheme": scheme(), PROJECT / "xcshareddata/xcschemes/VonoNotes.xcscheme": scheme("VonoNotes")}
    for path, content in outputs.items():
        if args.check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit(f"Stale or missing project file: {path.relative_to(ROOT)}. Run scripts/generate-project.py.")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    print("Xcode project source inventory is current." if args.check else "Generated VonoTation.xcodeproj and shared scheme.")

if __name__ == "__main__":
    main()
