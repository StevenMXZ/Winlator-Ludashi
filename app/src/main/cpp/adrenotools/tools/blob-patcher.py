#!/usr/bin/python3
import sys, os

def patch_lib_base(in_dir, out_dir, libs, in_name, out_name):
    with open(in_dir + in_name, "rb") as inf:
        with open(out_dir + out_name, "wb") as outf:
            content = inf.read()
            for lib in libs:
                print(out_name + ": repl:", lib[0], "->", lib[1])
                content = content.replace(lib[0].encode(), lib[1].encode())

            content = content.replace(in_name.encode(), out_name.encode())
            outf.write(content);

def patch_lib(in_dir, out_dir, libs, name):
    patch_lib_base(in_dir, out_dir, libs, "lib" + name + ".so", "not" + name + ".so")

def patch_references(lib_dir, out_dir, vulkan_libname, vulkan_newname, shim_mapper):
    try:
        os.mkdir(out_dir)
        os.mkdir(out_dir + "/hw")
    except:
        pass

    replacement_list = [("libgsl.so", "notgsl.so"), ("libllvm-glnext.so", "notllvm-glnext.so"), ("libdmabufheap.so", "notdmabufheap.so"), ("libadreno_utils.so", "notadreno_utils.so"), ("libllvm-qgl.so", "notllvm-qgl.so")]

    if (shim_mapper < 2):
        replacement_list += [("vendor.qti.hardware.display.mapper@2.0.so", "vendor.qti.hardware.display.mappershim.so"),
                             ("android.hardware.graphics.mapper@2.0.so", "android.hardware.graphics.mappershim.so"),
                             ("vendor.qti.hardware.display.mapperextensions@1.0.so", "vendor.qti.hardware.display.mapperextensionsshim.so"),
                             ("vendor.qti.hardware.display.mapperextensions@1.1.so", "vendor.qti.hardware.display.mapperextensionsshim.so")]
    if (shim_mapper < 3):
        replacement_list += [("vendor.qti.hardware.display.mapper@3.0.so", "vendor.qti.hardware.display.mappershim.so"),
                             ("android.hardware.graphics.mapper@3.0.so", "android.hardware.graphics.mappershim.so")]
    if (shim_mapper < 4):
        replacement_list += [("vendor.qti.hardware.display.mapper@4.0.so", "vendor.qti.hardware.display.mappershim.so"),
                             ("android.hardware.graphics.mapper@4.0.so", "android.hardware.graphics.mappershim.so")]

    for lib in ["gsl", "llvm-glnext", "dmabufheap", "adreno_utils", "llvm-qgl"]:
        try:
            patch_lib(lib_dir, out_dir, replacement_list, lib)
        except:
            pass

    patch_lib_base(lib_dir + "/hw/", out_dir + "/hw/", replacement_list, vulkan_libname, vulkan_newname)

if (len(sys.argv)!= 6):
    print("<in> <out> <vk soname> <vk soname new> <device mapper ver>")
    sys.exit()



root_path = sys.argv[1]
out_path = sys.argv[2]
vulkan_libname = sys.argv[3]
vulkan_newname = sys.argv[4]
shim_mapper = int(sys.argv[5])
vendor_path = root_path + "/vendor/"

if (len(vulkan_newname) != len(vulkan_libname)):
    print("Errror")
    sys.exit()

for libdir in ["/lib64/", "/lib/"]:
    patch_references(vendor_path + libdir, out_path + libdir, vulkan_libname, vulkan_newname, shim_mapper)
print("done!")

