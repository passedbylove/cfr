package org.benf.cfr.reader;

import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.entities.ClassFile;
import org.benf.cfr.reader.entities.Method;
import org.benf.cfr.reader.relationship.MemberNameResolver;
import org.benf.cfr.reader.state.DCCommonState;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.state.TypeUsageCollectorImpl;
import org.benf.cfr.reader.util.CannotLoadClassException;
import org.benf.cfr.reader.util.MiscConstants;
import org.benf.cfr.reader.util.MiscUtils;
import org.benf.cfr.reader.util.collections.Functional;
import org.benf.cfr.reader.util.collections.ListFactory;
import org.benf.cfr.reader.util.functors.Predicate;
import org.benf.cfr.reader.util.getopt.Options;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.benf.cfr.reader.util.output.*;

import java.util.List;

class Driver {

    static void doClass(DCCommonState dcCommonState, String path, boolean skipInnerClass, DumperFactory dumperFactory) {
        Options options = dcCommonState.getOptions();
        IllegalIdentifierDump illegalIdentifierDump = IllegalIdentifierDump.Factory.get(options);
        Dumper d = new ToStringDumper(); // sentinel dumper.
        ExceptionDumper ed = dumperFactory.getExceptionDumper();
        try {
            SummaryDumper summaryDumper = new NopSummaryDumper();
            ClassFile c = dcCommonState.getClassFileMaybePath(path);
            if (skipInnerClass && c.isInnerClass()) return;

            dcCommonState.configureWith(c);
            dumperFactory.getProgressDumper().analysingType(c.getClassType());

            // This may seem odd, but we want to make sure we're analysing the version
            // from the cache.  Because we might have been fed a random filename
            try {
                c = dcCommonState.getClassFile(c.getClassType());
            } catch (CannotLoadClassException ignore) {
            }

            if (options.getOption(OptionsImpl.DECOMPILE_INNER_CLASSES)) {
                c.loadInnerClasses(dcCommonState);
            }
            if (options.getOption(OptionsImpl.RENAME_DUP_MEMBERS)) {
                MemberNameResolver.resolveNames(dcCommonState, ListFactory.newList(dcCommonState.getClassCache().getLoadedTypes()));
            }

            // THEN analyse.
            c.analyseTop(dcCommonState);
            /*
             * Perform a pass to determine what imports / classes etc we used / failed.
             */
            TypeUsageCollector collectingDumper = new TypeUsageCollectorImpl(c);
            c.collectTypeUsages(collectingDumper);

            d = dumperFactory.getNewTopLevelDumper(c.getClassType(), summaryDumper, collectingDumper.getTypeUsageInformation(), illegalIdentifierDump);

            String methname = options.getOption(OptionsImpl.METHODNAME);
            if (methname == null) {
                c.dump(d);
            } else {
                try {
                    for (Method method : c.getMethodByName(methname)) {
                        method.dump(d, true);
                    }
                } catch (NoSuchMethodException e) {
                    throw new IllegalArgumentException("No such method '" + methname + "'.");
                }
            }
            d.print("");
        } catch (RuntimeException e) {
            ed.noteException(path, null, e);
        } finally {
            if (d != null) d.close();
        }
    }

    static void doJar(DCCommonState dcCommonState, String path, DumperFactory dumperFactory) {
        Options options = dcCommonState.getOptions();
        IllegalIdentifierDump illegalIdentifierDump = IllegalIdentifierDump.Factory.get(options);
        SummaryDumper summaryDumper = null;
        boolean silent;
        try {
            final boolean lomem = options.getOption(OptionsImpl.LOMEM);
            final Predicate<String> matcher = MiscUtils.mkRegexFilter(options.getOption(OptionsImpl.JAR_FILTER), true);
            ProgressDumper progressDumper = dumperFactory.getProgressDumper();
            silent = options.getOption(OptionsImpl.SILENT);
            summaryDumper = dumperFactory.getSummaryDumper();
            summaryDumper.notify("Summary for " + path);
            summaryDumper.notify(MiscConstants.CFR_HEADER_BRA + " " + MiscConstants.CFR_VERSION);
            progressDumper.analysingPath(path);
            List<JavaTypeInstance> types = dcCommonState.explicitlyLoadJar(path);
            types = Functional.filter(types, new Predicate<JavaTypeInstance>() {
                @Override
                public boolean test(JavaTypeInstance in) {
                    return matcher.test(in.getRawName());
                }
            });
            /*
             * If resolving names, we need a first pass...... otherwise foreign referents will
             * not see the renaming, depending on order of class files....
             */
            if (options.getOption(OptionsImpl.RENAME_DUP_MEMBERS) ||
                options.getOption(OptionsImpl.RENAME_ENUM_MEMBERS)) {
                MemberNameResolver.resolveNames(dcCommonState, types);
            }
            /*
             * If we're working on a case insensitive file system (OH COME ON!) then make sure that
             * we don't have any collisions.
             */
            for (JavaTypeInstance type : types) {
                Dumper d = new ToStringDumper();  // Sentinel dumper.
                try {
                    ClassFile c = dcCommonState.getClassFile(type);
                    // Don't explicitly dump inner classes.  But make sure we ask the CLASS if it's
                    // an inner class, rather than using the name, as scala tends to abuse '$'.
                    if (c.isInnerClass()) { d = null; continue; }
                    if (!silent) {
                        progressDumper.analysingType(type);
                    }
                    if (options.getOption(OptionsImpl.DECOMPILE_INNER_CLASSES)) {
                        c.loadInnerClasses(dcCommonState);
                    }
                    // THEN analyse.
                    c.analyseTop(dcCommonState);

                    TypeUsageCollector collectingDumper = new TypeUsageCollectorImpl(c);
                    c.collectTypeUsages(collectingDumper);
                    d = dumperFactory.getNewTopLevelDumper(c.getClassType(), summaryDumper, collectingDumper.getTypeUsageInformation(), illegalIdentifierDump);

                    c.dump(d);
                    d.print("\n");
                    d.print("\n");
                    if (lomem) {
                        c.releaseCode();
                    }
                } catch (Dumper.CannotCreate e) {
                    throw e;
                } catch (RuntimeException e) {
                    d.print(e.toString()).print("\n").print("\n").print("\n");
                } finally {
                    if (d != null) d.close();
                }

            }
        } catch (RuntimeException e) {
            dumperFactory.getExceptionDumper().noteException(path, "Exception analysing jar", e);
            if (summaryDumper != null) summaryDumper.notify("Exception analysing jar " + e);
        } finally {
            if (summaryDumper != null) {
                summaryDumper.close();
            }
        }
    }
}
