/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.jk.ant.compilers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Vector;

import org.apache.jk.ant.JkData;
import org.apache.jk.ant.SoTask;
import org.apache.jk.ant.Source;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Commandline;
import org.apache.tools.ant.util.GlobPatternMapper;

/**
 * Link using libtool.
 * 
 * @author Costin Manolache
 */
public class MwldLinker extends LinkerAdapter {
    GlobPatternMapper lo_mapper=new GlobPatternMapper();
    
    public MwldLinker() {
        super();
        lo_mapper.setFrom("*.c");
	lo_mapper.setTo("*.o");
    }

    public void setSoTask(SoTask so ) {
        this.so=so;
        so.setExtension(".nlm");
        so.duplicateTo( this );
        project.setProperty("netware", "true");

        Enumeration e=altSoFiles.elements();
        while (e.hasMoreElements())
        {
            JkData data = (JkData) e.nextElement();
            String altSo = data.getValue();
            if (altSo == null) 
                continue;
            else
            {
                so.setTarget(altSo);    // set it on the master copy
                setTarget(altSo);       // set it on ourself
                break;
            }
        }
    }

    public void execute() throws BuildException {
        findSourceFiles();
        link(this.srcList);
    }

    /** Link using libtool.
     */
    public boolean link(Vector srcList) throws BuildException {
        Commandline cmd = new Commandline();
        File linkOpt = new File(buildDir, "link.opt");
        File linkDef = new File(buildDir, "link.def");
        boolean useLibC = false;

        String libtool=project.getProperty("build.compiler.ld");
        if(libtool==null) libtool="mwldnlm";

        cmd.setExecutable( libtool );
        
        // All .obj files must be included
        project.log( "Linking " + buildDir + "/" + soFile + ".nlm");

        // create a .opt file and a .def file
        PrintWriter linkOptPw = null;
        PrintWriter linkDefPw = null;
        try
        {
            String libBase = project.getProperty("build.compiler.base");
            if (libBase == null) libBase = "\\tools\\mw\\5.3";
            linkOptPw = new PrintWriter(new FileWriter(linkOpt));
            linkDefPw = new PrintWriter(new FileWriter(linkDef));

            // write the link flags out
            linkOptPw.println("-warnings off");
            linkOptPw.println("-zerobss");
            linkOptPw.println("-o " + soFile + ".nlm");
            linkOptPw.println("-map " + soFile + ".map");
            linkOptPw.println("-nodefaults");

            // add debug information in if requested
            if (optG)
            {
                linkOptPw.println("-g");
                linkOptPw.println("-sym internal");
                linkOptPw.println("-sym codeview4");
                linkOptPw.println("-osym " + soFile + ".NCV");
            }

            // write out any additional link options
            Enumeration opts = linkOpts.elements();
            while( opts.hasMoreElements() ) {
                JkData opt = (JkData) opts.nextElement();
                String option = opt.getValue();
                if( option == null ) continue;

                linkOptPw.println( option );
                option = option.toLowerCase();

                // check to see if we are building using LibC
                if (option.indexOf("libc") > 0)
                    useLibC = true;
            }

            // add the default startup code to the list of objects
            if (useLibC)
                linkOptPw.println("-llibcpre.o");
            else
                linkOptPw.println(libBase + "\\lib\\nwpre.obj");

            // write the objects to link with to the .opt file
            for( int i=0; i<srcList.size(); i++ ) {
                Source source=(Source)srcList.elementAt(i);
                File srcF = source.getFile();
                String name=srcF.getName();
                String targetNA[]=lo_mapper.mapFileName( name );
                if( targetNA!=null )
                    linkOptPw.println( targetNA[0] );
            }
            linkOptPw.println("-commandfile link.def");

            // write the dependant modules to the .def file
            Enumeration mods = modules.elements();
            while( mods.hasMoreElements() ) {
                JkData mod = (JkData) mods.nextElement();
                String name = mod.getValue();
                if( name==null ) continue;
                linkDefPw.println("module " + name);
            }

            // write the imports to link with to the .def file
            Enumeration imps = imports.elements();
            while( imps.hasMoreElements() ) {
                JkData imp = (JkData) imps.nextElement();
                String name = imp.getValue();
                if( name==null ) continue;
                if (imp.isFile())
                    linkDefPw.println("Import @" + name);
                else
                    linkDefPw.println("Import " + name);
            }

            // write the exports to link with to the .def file
            Enumeration exps = exports.elements();
            while( exps.hasMoreElements() ) {
                JkData exp = (JkData) exps.nextElement();
                String name = exp.getValue();
                if( name==null ) continue;
                if (exp.isFile())
                    linkDefPw.println("Export @" + name);
                else
                    linkDefPw.println("Export " + name);
            }
        }
        catch (IOException ioe)
        {
            log("Caught IOException");
        }
        finally
        {
            if (linkOptPw != null)
            {
                linkOptPw.close();
            }

            if (linkDefPw != null)
            {
                linkDefPw.close();
            }
        }


        cmd.createArgument().setValue( "@link.opt" );
        int result=execute( cmd );
        if( result!=0 ) {
            log("Link failed " + result );
            log("Command:" + cmd.toString());
            log("Output:" );
            if( outputstream!=null ) 
                log( outputstream.toString());
            log("StdErr:" );
            if( errorstream!=null ) 
                log( errorstream.toString());
            
            throw new BuildException("Link failed " + soFile);
        }
        if (null == project.getProperty("save.optionFiles"))
        {
            linkOpt.delete();
            linkDef.delete();
        }
        closeStreamHandler();
        return true;
    }
}

