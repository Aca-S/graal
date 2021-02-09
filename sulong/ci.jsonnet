# File is formatted with
# `jsonnetfmt --indent 2 --max-blank-lines 2 --sort-imports --string-style d --comment-style h -i ci.jsonnet`
{
  local common = import "../common.jsonnet",
  local composable = (import "../common-utils.libsonnet").composable,
  local sulong_deps = composable((import "../common.json").sulong.deps),

  local linux_amd64 = common["linux-amd64"],
  local linux_aarch64 = common["linux-aarch64"],
  local darwin_amd64 = common["darwin-amd64"],

  jdk8:: common.oraclejdk8,

  labsjdk_ce_11: common["labsjdk-ce-11"] {
    downloads+: {
      # FIXME: do we really need to set EXTRA_JAVA_HOMES to an empty list?
      EXTRA_JAVA_HOMES: { pathlist: [] },
    },
  },

  sulong_linux_amd64:: linux_amd64 + sulong_deps.linux,
  sulong_linux_aarch64:: linux_aarch64 + sulong_deps.linux,
  sulong_darwin_amd64:: darwin_amd64 + sulong_deps.darwin,

  gate:: {
    targets+: ["gate"],
  },

  sulong_weekly_notifications:: {
    notify_groups:: ["sulong"],
  },

  sulong_common:: {
    environment+: {
      TRUFFLE_STRICT_OPTION_DEPRECATION: "true",
    },
    setup+: [
      ["cd", "./sulong"],
    ],
  },

  style:: {
    packages+: {
      ruby: "==2.1.0",  # for mdl
    },
  },

  sulong_gateTest_default_tools:: {
    environment+: {
      CLANG_LLVM_AS: "llvm-as",
      CLANG_LLVM_LINK: "llvm-link",
      CLANG_LLVM_DIS: "llvm-dis",
      CLANG_LLVM_OPT: "opt",
    },
  },


  llvm38:: $.sulong_gateTest_default_tools + {
    packages+: {
      llvm: "==3.8",
    },
    environment+: {
      NO_FEMBED_BITCODE: "true",
      CLANG_CC: "clang-3.8",
      CLANG_CXX: "clang-3.8 --driver-mode=g++",
      CLANG_LLVM_OBJCOPY: "objcopy",
      CLANG_NO_OPTNONE: "1",
      CFLAGS: "-Wno-error",
    },
  },

  llvm4:: $.sulong_gateTest_default_tools + {
    packages+: {
      llvm: "==4.0.1",
    },
    environment+: {
      CLANG_CC: "clang-4.0",
      CLANG_CXX: "clang-4.0 --driver-mode=g++",
      CLANG_LLVM_OBJCOPY: "objcopy",
      CLANG_NO_OPTNONE: "1",
      CFLAGS: "-Wno-error",
    },
  },

  llvm6:: $.sulong_gateTest_default_tools + {
    packages+: {
      llvm: "==6.0.1",
    },
    environment+: {
      CLANG_CC: "clang-6.0",
      CLANG_CXX: "clang-6.0 --driver-mode=g++",
      CFLAGS: "-Wno-error",
    },
  },

  llvm8: $.sulong_gateTest_default_tools + {
    packages+: {
      llvm: "==8.0.0",
    },
    environment+: {
      CLANG_CC: "clang-8",
      CLANG_CXX: "clang-8 --driver-mode=g++",
      CFLAGS: "-Wno-error",
    },
  },

  llvmBundled:: {},

  llvm4_darwin_fix:: {
    # FIXME: We prune `null` entries to produce the original result.
    # Eventually, we should canonicalize this.
    environment: std.prune(super.environment + {
      CPPFLAGS: "-g",
      CFLAGS: null,
      CLANG_LLVM_OBJCOPY: null,
    }),
    timelimit: "0:45:00",
  },

  llvmBundled_darwin_fix: {
    # nothing to do
    environment+: {
      LD_LIBRARY_PATH: "$BUILD_DIR/main/sulong/mxbuild/darwin-amd64/SULONG_LLVM_ORG/lib:$LD_LIBRARY_PATH",
    },
    timelimit: "0:45:00",
  },

  requireGCC: {
    packages+: {
      gcc: "==6.1.0",
    },
    downloads+: {
      DRAGONEGG_GCC: { name: "gcc+dragonegg", version: "4.6.4-1", platformspecific: true },
      DRAGONEGG_LLVM: { name: "clang+llvm", version: "3.2", platformspecific: true },
    },
  },

  requireGMP:: {
    downloads+: {
      LIBGMP: { name: "libgmp", version: "6.1.0", platformspecific: true },
    },
    environment+: {
      CPPFLAGS: "-g -I$LIBGMP/include",
      LD_LIBRARY_PATH: "$LIBGMP/lib:$LD_LIBRARY_PATH",
      LDFLAGS: "-L$LIBGMP/lib",
    },
  },

  sulong_ruby_downstream_test: {
    packages+: {
      ruby: "==2.6.3",
    },
    run: [
      [
        "mx",
        "testdownstream",
        "--repo",
        "https://github.com/graalvm/truffleruby.git",
        "--mx-command",
        "--dynamicimports /sulong ruby_testdownstream_sulong",
      ],
    ],
    timelimit: "45:00",
  },

  sulong_gate_generated_sources: {
    run: [
      ["mx", "build", "--dependencies", "LLVM_TOOLCHAIN"],
      ["mx", "create-generated-sources"],
      ["git", "diff", "--exit-code", "."],
    ],
  },

  sulong_coverage_linux: {
    run: [
      ["mx", "--jacoco-whitelist-package", "com.oracle.truffle.llvm", "--jacoco-exclude-annotation", "@GeneratedBy", "gate", "--tags", "build,sulongCoverage", "--jacocout", "html"],
      # $SONAR_HOST_URL might not be set [GR-28642],
      ["test", "-z", "$SONAR_HOST_URL", "||", "mx", "--jacoco-whitelist-package", "com.oracle.truffle.llvm", "--jacoco-exclude-annotation", "@GeneratedBy", "sonarqube-upload", "-Dsonar.host.url=$SONAR_HOST_URL", "-Dsonar.projectKey=com.oracle.graalvm.sulong", "-Dsonar.projectName=GraalVM - Sulong", "--exclude-generated"],
      ["mx", "--jacoco-whitelist-package", "com.oracle.truffle.llvm", "--jacoco-exclude-annotation", "@GeneratedBy", "coverage-upload"],
    ],
    targets: ["weekly"],
    timelimit: "1:45:00",
  },

  local sulong_test_toolchain = [
    ["mx", "build", "--dependencies", "SULONG_TEST"],
    ["mx", "unittest", "--verbose", "-Dsulongtest.toolchainPathPattern=SULONG_BOOTSTRAP_TOOLCHAIN", "ToolchainAPITest"],
    ["mx", "--env", "toolchain-only", "build"],
    ["set-export", "SULONG_BOOTSTRAP_GRAALVM", ["mx", "--env", "toolchain-only", "graalvm-home"]],
    ["mx", "unittest", "--verbose", "-Dsulongtest.toolchainPathPattern=GRAALVM_TOOLCHAIN_ONLY", "ToolchainAPITest"],
  ],

  builds: [
    $.gate + $.sulong_common + $.style + $.jdk8 + $.sulong_linux_amd64 + common.eclipse { name: "gate-sulong-style", run: [["mx", "gate", "--tags", "style"]] },
    $.gate + $.sulong_common + $.style + $.jdk8 + $.sulong_linux_amd64 + common.eclipse + common.jdt + { name: "gate-sulong-fullbuild", run: [["mx", "gate", "--tags", "fullbuild"]] },
    $.gate + $.sulong_common + $.jdk8 + linux_amd64 + $.sulong_gate_generated_sources { name: "gate-sulong-generated-sources" },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP + $.requireGCC + {
      name: "gate-sulong-misc",
      run: [
        ["mx", "gate", "--tags", "build,sulongMisc"],
      ] + sulong_test_toolchain,
    },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP + $.requireGCC + { name: "gate-sulong-parser", run: [["mx", "gate", "--tags", "build,parser"]] },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP { name: "gate-sulong-gcc_c", run: [["mx", "gate", "--tags", "build,gcc_c"]], timelimit: "45:00" },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP { name: "gate-sulong-gcc_cpp", run: [["mx", "gate", "--tags", "build,gcc_cpp"]], timelimit: "45:00" },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP + $.requireGCC + { name: "gate-sulong-gcc_fortran", run: [["mx", "gate", "--tags", "build,gcc_fortran"]] },
    # No more testing on llvm 3.8 [GR-21735]
    # $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvm38 + $.requireGMP + $.requireGCC + { name: "gate-sulong-basic_v38", run: [["mx", "gate", "--tags", "build,sulongBasic,nwcc,llvm"]] },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvm4 + $.requireGMP + $.requireGCC + { name: "gate-sulong-basic_v40", run: [["mx", "gate", "--tags", "build,sulongBasic,nwcc,llvm"]] },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvm6 + $.requireGMP + $.requireGCC + { name: "gate-sulong-basic_v60", run: [["mx", "gate", "--tags", "build,sulongBasic,nwcc,llvm"]] },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvm8 + $.requireGMP + $.requireGCC + { name: "gate-sulong-basic_v80", run: [["mx", "gate", "--tags", "build,sulongBasic,nwcc,llvm"]] },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP + $.requireGCC + { name: "gate-sulong-basic_bundled-llvm", run: [["mx", "gate", "--tags", "build,sulongBasic,sulongLL,nwcc,llvm,toolchain"]] },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_darwin_amd64 + $.llvm4 + $.llvm4_darwin_fix + { name: "gate-sulong-basic_mac", run: [["mx", "gate", "--tags", "build,sulongBasic,nwcc,llvm,toolchain"]] },
    $.gate + $.sulong_common + $.jdk8 + $.sulong_darwin_amd64 + $.llvmBundled + $.llvmBundled_darwin_fix + { name: "gate-sulong-basic_bundled-llvm_mac", run: [["mx", "gate", "--tags", "build,sulongBasic,sulongLL,nwcc,llvm,toolchain"]] },

    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP + $.sulong_ruby_downstream_test + { name: "gate-sulong-ruby-downstream" },

    $.gate + $.sulong_common + $.labsjdk_ce_11 + $.sulong_linux_aarch64 + $.llvmBundled + $.requireGMP + { name: "gate-sulong_bundled-llvm-linux-aarch64", run: [["mx", "gate", "--tags", "build,sulong,sulongLL,interop,linker,debug,irdebug,bitcodeFormat,otherTests,llvm"]], timelimit: "30:00" },
    $.gate + $.sulong_common + $.labsjdk_ce_11 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP + {
      name: "gate-sulong-build_bundled-llvm-linux-amd64-labsjdk-ce-11",
      run: [
        ["mx", "gate", "--tags", "build"],
      ] + sulong_test_toolchain,
    },

    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP { name: "gate-sulong-strict-native-image", run: [
      ["mx", "--dynamicimports", "/substratevm,/tools", "--native-images=lli", "--extra-image-builder-argument=-H:+TruffleCheckBlackListedMethods", "gate", "--tags", "build"],
    ] },

    $.gate + $.sulong_common + $.jdk8 + $.sulong_linux_amd64 + $.llvmBundled + $.requireGMP + $.requireGCC + $.sulong_weekly_notifications + $.sulong_coverage_linux { name: "weekly-sulong-coverage" },
  ],
}
