// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.AutoCpuConverter;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.PerLabelOptions;
import com.google.devtools.build.lib.analysis.skylark.annotations.SkylarkConfigurationField;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.rules.cpp.CppConfigurationLoader.CppConfigurationParameters;
import com.google.devtools.build.lib.rules.cpp.Link.LinkingMode;
import com.google.devtools.build.lib.skylarkbuildapi.cpp.CppConfigurationApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CrosstoolRelease;
import javax.annotation.Nullable;

/**
 * This class represents the C/C++ parts of the {@link BuildConfiguration}, including the host
 * architecture, target architecture, compiler version, and a standard library version. It has
 * information about the tools locations and the flags required for compiling.
 *
 * <p>Before {@link CppConfiguration} is created, two things need to be done:
 *
 * <ol>
 *   <li>choosing a {@link CcToolchainRule} label from {@code toolchains} map attribute of {@link
 *       CcToolchainSuiteRule}.
 *   <li>selection of a {@link
 *       com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig.CToolchain} from the
 *       CROSSTOOL file.
 * </ol>
 *
 * <p>The process goes as follows:
 *
 * <p>Check for existence of {@link CcToolchainSuiteRule}.toolchains[<cpu>|<compiler>], if
 * --compiler is specified, otherwise check for {@link CcToolchainSuiteRule}.toolchains[<cpu>].
 *
 * <ul>
 *   <li>if a value is found, load the {@link CcToolchainRule} rule and look for the {@code
 *       toolchain_identifier} attribute.
 *   <li>
 *       <ul>
 *         <li>if the attribute exists, loop through all the {@code CToolchain}s in CROSSTOOL and
 *             select the one with the matching toolchain identifier.
 *         <li>otherwise fall back to selecting the CToolchain from CROSSTOOL by matching the --cpu
 *             and --compiler values.
 *       </ul>
 *   <li>If a value is not found, select the CToolchain from CROSSTOOL by matching the --cpu and
 *       --compiler values, and construct the key as follows: <toolchain.cpu>|<toolchain.compiler>.
 * </ul>
 */
@Immutable
public final class CppConfiguration extends BuildConfiguration.Fragment
    implements CppConfigurationApi<InvalidConfigurationException> {
  /**
   * String indicating a Mac system, for example when used in a crosstool configuration's host or
   * target system name.
   */
  public static final String MAC_SYSTEM_NAME = "x86_64-apple-macosx";

  /** String constant for CC_FLAGS make variable name */
  public static final String CC_FLAGS_MAKE_VARIABLE_NAME = "CC_FLAGS";

  /**
   * An enumeration of all the tools that comprise a toolchain.
   */
  public enum Tool {
    AR("ar"),
    CPP("cpp"),
    GCC("gcc"),
    GCOV("gcov"),
    GCOVTOOL("gcov-tool"),
    LD("ld"),
    NM("nm"),
    OBJCOPY("objcopy"),
    OBJDUMP("objdump"),
    STRIP("strip"),
    DWP("dwp"),
    LLVM_PROFDATA("llvm-profdata");

    private final String namePart;

    private Tool(String namePart) {
      this.namePart = namePart;
    }

    public String getNamePart() {
      return namePart;
    }
  }

  /**
   * Values for the --hdrs_check option. Note that Bazel only supports and will default to "strict".
   */
  public enum HeadersCheckingMode {
    /**
     * Legacy behavior: Silently allow any source header file in any of the directories of the
     * containing package to be included by sources in this rule and dependent rules.
     */
    LOOSE,
    /** Disallow undeclared headers. */
    STRICT;

    public static HeadersCheckingMode getValue(String value) {
      if (value.equalsIgnoreCase("loose") || value.equalsIgnoreCase("warn")) {
        return LOOSE;
      }
      if (value.equalsIgnoreCase("strict")) {
        return STRICT;
      }
      throw new IllegalArgumentException();
    }
  }

  /**
   * --dynamic_mode parses to DynamicModeFlag, but AUTO will be translated based on platform,
   * resulting in a DynamicMode value.
   */
  public enum DynamicMode     { OFF, DEFAULT, FULLY }

  /**
   * This enumeration is used for the --strip option.
   */
  public enum StripMode {

    ALWAYS("always"),       // Always strip.
    SOMETIMES("sometimes"), // Strip iff compilationMode == FASTBUILD.
    NEVER("never");         // Never strip.

    private final String mode;

    private StripMode(String mode) {
      this.mode = mode;
    }

    @Override
    public String toString() {
      return mode;
    }
  }

  /**
   * This macro will be passed as a command-line parameter (eg. -DBUILD_FDO_TYPE="AUTOFDO"). For
   * possible values see {@code CppModel.getFdoBuildStamp()}.
   */
  public static final String FDO_STAMP_MACRO = "BUILD_FDO_TYPE";

  private final Label crosstoolTop;
  /**
   * cc_toolchain_suite allows to override CROSSTOOL by using proto attribute. This attribute value
   * is stored here so cc_toolchain can access it in the analysis. Don't use this for anything, it
   * will be removed when b/113849758 is fixed. If you do, I'll send bubo to take your keyboard
   * away.
   */
  @Deprecated private final CrosstoolRelease crosstoolFromCcToolchainProtoAttribute;

  private final String transformedCpuFromOptions;
  private final String compilerFromOptions;
  // TODO(lberki): desiredCpu *should* be always the same as targetCpu, except that we don't check
  // that the CPU we get from the toolchain matches BuildConfiguration.Options.cpu . So we store
  // it here so that the output directory doesn't depend on the CToolchain. When we will eventually
  // verify that the two are the same, we can remove one of desiredCpu and targetCpu.
  private final String desiredCpu;

  private final PathFragment fdoPath;
  private final Label fdoOptimizeLabel;

  private final ImmutableList<String> conlyopts;

  private final ImmutableList<String> copts;
  private final ImmutableList<String> cxxopts;

  private final ImmutableList<String> linkopts;
  private final ImmutableList<String> ltoindexOptions;
  private final ImmutableList<String> ltobackendOptions;

  private final CppOptions cppOptions;

  // The dynamic mode for linking.
  private final boolean stripBinaries;
  private final CompilationMode compilationMode;

  private final CppToolchainInfo cppToolchainInfo;

  static CppConfiguration create(CppConfigurationParameters params)
      throws InvalidConfigurationException {
    CppOptions cppOptions = params.cppOptions;
    CppToolchainInfo cppToolchainInfo =
        CppToolchainInfo.create(
            params.crosstoolTop.getPackageIdentifier().getPathUnderExecRoot(),
            params.ccToolchainLabel,
            params.ccToolchainConfigInfo,
            cppOptions.disableLegacyCrosstoolFields,
            cppOptions.disableCompilationModeFlags,
            cppOptions.disableLinkingModeFlags);

    CompilationMode compilationMode = params.commonOptions.compilationMode;

    ImmutableList.Builder<String> linkoptsBuilder = ImmutableList.builder();
    linkoptsBuilder.addAll(cppOptions.linkoptList);
    if (cppOptions.experimentalOmitfp) {
      linkoptsBuilder.add("-Wl,--eh-frame-hdr");
    }

    return new CppConfiguration(
        params.crosstoolTop,
        params.crosstoolFromCcToolchainProtoAttribute,
        params.transformedCpu,
        params.compiler,
        Preconditions.checkNotNull(params.commonOptions.cpu),
        params.fdoPath,
        params.fdoOptimizeLabel,
        ImmutableList.copyOf(cppOptions.conlyoptList),
        ImmutableList.copyOf(cppOptions.coptList),
        ImmutableList.copyOf(cppOptions.cxxoptList),
        linkoptsBuilder.build(),
        ImmutableList.copyOf(cppOptions.ltoindexoptList),
        ImmutableList.copyOf(cppOptions.ltobackendoptList),
        cppOptions,
        (cppOptions.stripBinaries == StripMode.ALWAYS
            || (cppOptions.stripBinaries == StripMode.SOMETIMES
                && compilationMode == CompilationMode.FASTBUILD)),
        compilationMode,
        cppToolchainInfo);
  }

  private CppConfiguration(
      Label crosstoolTop,
      CrosstoolRelease crosstoolFromCcToolchainProtoAttribute,
      String transformedCpuFromOptions,
      String compilerFromOptions,
      String desiredCpu,
      PathFragment fdoPath,
      Label fdoOptimizeLabel,
      ImmutableList<String> conlyopts,
      ImmutableList<String> copts,
      ImmutableList<String> cxxopts,
      ImmutableList<String> linkopts,
      ImmutableList<String> ltoindexOptions,
      ImmutableList<String> ltobackendOptions,
      CppOptions cppOptions,
      boolean stripBinaries,
      CompilationMode compilationMode,
      CppToolchainInfo cppToolchainInfo) {
    this.crosstoolTop = crosstoolTop;
    this.crosstoolFromCcToolchainProtoAttribute = crosstoolFromCcToolchainProtoAttribute;
    this.transformedCpuFromOptions = transformedCpuFromOptions;
    this.compilerFromOptions = compilerFromOptions;
    this.desiredCpu = desiredCpu;
    this.fdoPath = fdoPath;
    this.fdoOptimizeLabel = fdoOptimizeLabel;
    this.conlyopts = conlyopts;
    this.copts = copts;
    this.cxxopts = cxxopts;
    this.linkopts = linkopts;
    this.ltoindexOptions = ltoindexOptions;
    this.ltobackendOptions = ltobackendOptions;
    this.cppOptions = cppOptions;
    this.stripBinaries = stripBinaries;
    this.compilationMode = compilationMode;
    this.cppToolchainInfo = cppToolchainInfo;
  }

  @VisibleForTesting
  static LinkingMode importLinkingMode(CrosstoolConfig.LinkingMode mode) {
    switch (mode.name()) {
      case "FULLY_STATIC":
        return LinkingMode.LEGACY_FULLY_STATIC;
      case "MOSTLY_STATIC_LIBRARIES":
        return LinkingMode.LEGACY_MOSTLY_STATIC_LIBRARIES;
      case "MOSTLY_STATIC":
        return LinkingMode.STATIC;
      case "DYNAMIC":
        return LinkingMode.DYNAMIC;
      default:
        throw new IllegalArgumentException(
            String.format("Linking mode '%s' not known.", mode.name()));
    }
  }

  /** Returns the {@link CppToolchainInfo} used by this configuration. */
  public CppToolchainInfo getCppToolchainInfo() {
    return cppToolchainInfo;
  }

  /**
   * Returns the toolchain identifier, which uniquely identifies the compiler version, target libc
   * version, and target cpu.
   */
  public String getToolchainIdentifier() {
    return cppToolchainInfo.getToolchainIdentifier();
  }

  /** Returns the label of the CROSSTOOL for this configuration. */
  public Label getCrosstoolTop() {
    return crosstoolTop;
  }

  /**
   * Returns the path of the crosstool.
   */
  public PathFragment getCrosstoolTopPathFragment() {
    return cppToolchainInfo.getCrosstoolTopPathFragment();
  }

  @Override
  public String toString() {
    return cppToolchainInfo.toString();
  }

  /**
   * Returns the path fragment that is either absolute or relative to the execution root that can be
   * used to execute the given tool.
   *
   * <p>Deprecated: Use {@link CcToolchainProvider#getToolPathFragment(Tool)}
   */
  @Deprecated
  public PathFragment getToolPathFragment(CppConfiguration.Tool tool) {
    return cppToolchainInfo.getToolPathFragment(tool);
  }

  /** Returns the label of the <code>cc_compiler</code> rule for the C++ configuration. */
  @SkylarkConfigurationField(
      name = "cc_toolchain",
      doc = "The label of the target describing the C++ toolchain",
      defaultLabel = "//tools/cpp:crosstool",
      defaultInToolRepository = true)
  public Label getRuleProvidingCcToolchainProvider() {
      return crosstoolTop;
  }

  /**
   * Returns the configured features of the toolchain. Rules should not call this directly, but
   * instead use {@code CcToolchainProvider.getFeatures}.
   */
  public CcToolchainFeatures getFeatures() {
    return cppToolchainInfo.getFeatures();
  }

  /**
   * Returns the configured current compilation mode. Rules should not call this directly, but
   * instead use {@code CcToolchainProvider.getCompilationMode}.
   */
  public CompilationMode getCompilationMode() {
    return compilationMode;
  }


  public boolean hasSharedLinkOption() {
    return linkopts.contains("-shared");
  }

  /** Returns the set of command-line LTO indexing options. */
  public ImmutableList<String> getLtoIndexOptions() {
    return ltoindexOptions;
  }

  /** Returns the set of command-line LTO backend options. */
  public ImmutableList<String> getLtoBackendOptions() {
    return ltobackendOptions;
  }

  @SkylarkCallable(
      name = "minimum_os_version",
      doc = "The minimum OS version for C/C++ compilation.")
  public String getMinimumOsVersion() {
    return cppOptions.minimumOsVersion;
  }

  /** Returns the value of the --dynamic_mode flag. */
  public DynamicMode getDynamicModeFlag() {
    return cppOptions.dynamicMode;
  }

  public boolean getLinkCompileOutputSeparately() {
    return cppOptions.linkCompileOutputSeparately;
  }

  @SkylarkConfigurationField(
      name = "stl",
      doc = "The label of the STL target",
      defaultLabel = "//third_party/stl",
      defaultInToolRepository = false
  )
  public Label getSkylarkStl() {
    try {
      return Label.parseAbsolute("//third_party/stl", ImmutableMap.of());
    } catch (LabelSyntaxException e) {
      throw new IllegalStateException("STL label not formatted correctly", e);
    }
  }

  public boolean isFdo() {
    return cppOptions.isFdo();
  }

  /**
   * Returns whether or not to strip the binaries.
   */
  public boolean shouldStripBinaries() {
    return stripBinaries;
  }

  /**
   * Returns the additional options to pass to strip when generating a
   * {@code <name>.stripped} binary by this build.
   */
  public ImmutableList<String> getStripOpts() {
    return ImmutableList.copyOf(cppOptions.stripoptList);
  }

  /**
   * Returns whether temporary outputs from gcc will be saved.
   */
  public boolean getSaveTemps() {
    return cppOptions.saveTemps;
  }

  /**
   * Returns the {@link PerLabelOptions} to apply to the gcc command line, if
   * the label of the compiled file matches the regular expression.
   */
  public ImmutableList<PerLabelOptions> getPerFileCopts() {
    return ImmutableList.copyOf(cppOptions.perFileCopts);
  }

  /**
   * Returns the {@link PerLabelOptions} to apply to the LTO Backend command line, if the compiled
   * object matches the regular expression.
   */
  public ImmutableList<PerLabelOptions> getPerFileLtoBackendOpts() {
    return ImmutableList.copyOf(cppOptions.perFileLtoBackendOpts);
  }

  /**
   * Returns the custom malloc library label.
   */
  public Label customMalloc() {
    return cppOptions.customMalloc;
  }

  /**
   * Returns whether we are processing headers in dependencies of built C++ targets.
   */
  public boolean processHeadersInDependencies() {
    return cppOptions.processHeadersInDependencies;
  }

  /** Returns true if --fission contains the current compilation mode. */
  public boolean fissionIsActiveForCurrentCompilationMode() {
    return cppOptions.fissionModes.contains(compilationMode);
  }

  /** Returns true if --build_test_dwp is set on this build. */
  public boolean buildTestDwpIsActivated() {
    return cppOptions.buildTestDwp;
  }

  /**
   * Returns true if all C++ compilations should produce position-independent code, links should
   * produce position-independent executables, and dependencies with equivalent pre-built pic and
   * nopic versions should apply the pic versions. Returns false if default settings should be
   * applied (i.e. make no special provisions for pic code).
   */
  public boolean forcePic() {
    return cppOptions.forcePic;
  }

  /** Returns true if --start_end_lib is set on this build. */
  public boolean startEndLibIsRequested() {
    return cppOptions.useStartEndLib;
  }

  /**
   * @return value from the --cpu option transformed using {@link CpuTransformer}. If it was not
   *     passed explicitly, {@link AutoCpuConverter} will try to guess something reasonable.
   */
  public String getTransformedCpuFromOptions() {
    return transformedCpuFromOptions;
  }

  /** @return value from --compiler option, null if the option was not passed. */
  @Nullable
  public String getCompilerFromOptions() {
    return compilerFromOptions;
  }

  public boolean legacyWholeArchive() {
    return cppOptions.legacyWholeArchive;
  }

  public boolean getSymbolCounts() {
    return cppOptions.symbolCounts;
  }

  public boolean getInmemoryDotdFiles() {
    return cppOptions.inmemoryDotdFiles;
  }

  public boolean getParseHeadersVerifiesModules() {
    return cppOptions.parseHeadersVerifiesModules;
  }

  public boolean getUseInterfaceSharedObjects() {
    return cppOptions.useInterfaceSharedObjects;
  }

  /** Returns whether this configuration will use libunwind for stack unwinding. */
  public boolean isOmitfp() {
    return cppOptions.experimentalOmitfp;
  }

  /** Returns flags passed to Bazel by --copt option. */
  @Override
  public ImmutableList<String> getCopts() {
    return copts;
  }

  /** Returns flags passed to Bazel by --cxxopt option. */
  @Override
  public ImmutableList<String> getCxxopts() {
    return cxxopts;
  }

  /** Returns flags passed to Bazel by --conlyopt option. */
  @Override
  public ImmutableList<String> getConlyopts() {
    return conlyopts;
  }

  /** Returns flags passed to Bazel by --linkopt option. */
  @Override
  public ImmutableList<String> getLinkopts() {
    return linkopts;
  }

  @Override
  public void reportInvalidOptions(EventHandler reporter, BuildOptions buildOptions) {
    CppOptions cppOptions = buildOptions.get(CppOptions.class);
    if (stripBinaries) {
      boolean warn = cppOptions.coptList.contains("-g");
      for (PerLabelOptions opt : cppOptions.perFileCopts) {
        warn |= opt.getOptions().contains("-g");
      }
      if (warn) {
        reporter.handle(
            Event.warn(
                "Stripping enabled, but '--copt=-g' (or --per_file_copt=...@-g) specified. "
                    + "Debug information will be generated and then stripped away. This is "
                    + "probably not what you want! Use '-c dbg' for debug mode, or use "
                    + "'--strip=never' to disable stripping"));
      }
    }

    // FDO
    if (cppOptions.getFdoOptimize() != null && cppOptions.fdoProfileLabel != null) {
      reporter.handle(Event.error("Both --fdo_optimize and --fdo_profile specified"));
    }

    if (cppOptions.fdoInstrumentForBuild != null) {
      if (cppOptions.getFdoOptimize() != null || cppOptions.fdoProfileLabel != null) {
        reporter.handle(
            Event.error(
                "Cannot instrument and optimize for FDO at the same time. Remove one of the "
                    + "'--fdo_instrument' and '--fdo_optimize/--fdo_profile' options"));
      }
      if (!cppOptions.coptList.contains("-Wno-error")) {
        // This is effectively impossible. --fdo_instrument adds this value, and only invocation
        // policy could remove it.
        reporter.handle(Event.error("Cannot instrument FDO without --copt including -Wno-error."));
      }
    }

    // This is an assertion check vs. user error because users can't trigger this state.
    Verify.verify(
        !(buildOptions.get(BuildConfiguration.Options.class).isHost && cppOptions.isFdo()),
        "FDO state should not propagate to the host configuration");
  }

  @Override
  public String getOutputDirectoryName() {
    String toolchainPrefix = desiredCpu;
    if (!cppOptions.outputDirectoryTag.isEmpty()) {
      toolchainPrefix += "-" + cppOptions.outputDirectoryTag;
    }

    return toolchainPrefix;
  }

  /**
   * Returns true if we should share identical native libraries between different targets.
   */
  public boolean shareNativeDeps() {
    return cppOptions.shareNativeDeps;
  }

  public boolean isStrictSystemIncludes() {
    return cppOptions.strictSystemIncludes;
  }

  public String getFdoInstrument() {
    return cppOptions.fdoInstrumentForBuild;
  }

  public PathFragment getFdoPath() {
    return fdoPath;
  }

  public Label getFdoOptimizeLabel() {
    return fdoOptimizeLabel;
  }

  public Label getFdoPrefetchHintsLabel() {
    return cppOptions.getFdoPrefetchHintsLabel();
  }

  public Label getFdoProfileLabel() {
    return cppOptions.fdoProfileLabel;
  }

  public boolean isFdoAbsolutePathEnabled() {
    return cppOptions.enableFdoProfileAbsolutePath;
  }

  public boolean useLLVMCoverageMapFormat() {
    return cppOptions.useLLVMCoverageMapFormat;
  }

  public boolean disableLegacyCrosstoolFields() {
    return cppOptions.disableLegacyCrosstoolFields;
  }

  public boolean disableCompilationModeFlags() {
    return cppOptions.disableCompilationModeFlags;
  }

  public boolean disableLinkingModeFlags() {
    return cppOptions.disableLinkingModeFlags;
  }

  /**
   * cc_toolchain_suite allows to override CROSSTOOL by using proto attribute. This attribute value
   * is stored here so cc_toolchain can access it in the analysis. Don't use this for anything, it
   * will be removed when b/113849758 is fixed. If you do, I'll send bubo to take your keyboard
   * away.
   */
  @Deprecated
  public CrosstoolRelease getCrosstoolFromCcToolchainProtoAttribute() {
    return crosstoolFromCcToolchainProtoAttribute;
  }

  public boolean enableLinkoptsInUserLinkFlags() {
    return cppOptions.enableLinkoptsInUserLinkFlags;
  }

  public boolean disableEmittingStaticLibgcc() {
    return cppOptions.disableEmittingStaticLibgcc;
  }

  public boolean disableDepsetInUserFlags() {
    return cppOptions.disableDepsetInUserFlags;
  }

  public static PathFragment computeDefaultSysroot(String builtInSysroot) {
    if (builtInSysroot.isEmpty()) {
      return null;
    }
    if (!PathFragment.isNormalized(builtInSysroot)) {
      throw new IllegalArgumentException(
          "The built-in sysroot '" + builtInSysroot + "' is not normalized.");
    }
    return PathFragment.create(builtInSysroot);
  }

  boolean enableCcToolchainConfigInfoFromSkylark() {
    return cppOptions.enableCcToolchainConfigInfoFromSkylark;
  }

  /**
   * Returns the value of the libc top-level directory (--grte_top) as specified on the command line
   */
  public Label getLibcTopLabel() {
    return cppOptions.libcTopLabel;
  }
}
