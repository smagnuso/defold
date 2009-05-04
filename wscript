#! /usr/bin/env python

VERSION='0.1'
APPNAME='ddf'

srcdir = '.'
blddir = 'build'

import os, sys
sys.path = ["src"] + sys.path
import waf_ddf

def init():
    pass

def set_options(opt):
    opt.sub_options('src')
    opt.tool_options('compiler_cxx')

def configure(conf):
    conf.check_tool('compiler_cxx')
    conf.sub_config('src')

    conf.find_program('ddfc.py', var='DDFC', path_list = [os.path.abspath('src')], mandatory = True)

    if sys.platform == "darwin":
        platform = "darwin"
    elif sys.platform == "linux2":
        platform = "linux"
    elif sys.platform == "win32":
        platform = "win32"
    else:
        conf.fatal("Unable to determine platform")

    if platform == "linux" or platform == "darwin":
        conf.env['CXXFLAGS']='-g -D__STDC_LIMIT_MACROS'
    else:
        conf.env['CXXFLAGS']=['/Z7', '/MT', '/D__STDC_LIMIT_MACROS']
        conf.env.append_value('CPPPATH', "../src/win32")


    dynamo_home = os.getenv('DYNAMO_HOME')
    if not dynamo_home:
        conf.fatal("DYNAMO_HOME not set")

    dynamo_ext = os.path.join(dynamo_home, "ext")
    # TODO: WTF!!
    conf.env.append_value('CPPPATH', "../src")
    conf.env.append_value('CPPPATH', os.path.join(dynamo_ext, "include"))
    conf.env.append_value('CPPPATH', ".")
    conf.env.append_value('LIBPATH', os.path.join(dynamo_ext, "lib", platform))

    conf.env['LIB_PROTOBUF'] = 'protobuf'
    conf.env['LIB_GTEST'] = 'gtest'

def build(bld):
    bld.add_subdirs('src')

