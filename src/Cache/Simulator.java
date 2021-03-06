package Cache;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import com.csvreader.CsvWriter;

import Util.CmdLineParser;
import Cache.CacheItem.CacheReason;
import Database.DatabaseManager;

public class Simulator {

    /**
     * Database prepared sql statements.
     */
    
    static final String findCommit = "select id, date, is_bug_fix from scmlog "
        + "where repository_id =? and date between ? and ? order by date ASC";
    static final String findFile = "select file_name, type from actions_cache a, content_loc_cache c" +
    		" where a.file_id=c.file_id and a.commit_id=? and " +
    		"c.commit_id=? order by loc DESC";
    static final String findHunkId = "select hunks.id from hunks, files " +
    		"where hunks.file_id=files.id and file_name =? and commit_id =?";
    static final String findBugIntroCdate = "select date from hunk_blames, scmlog "
        + "where hunk_id =? and hunk_blames.bug_commit_id=scmlog.id";
    static final String findPid = "select id from repositories where id=?";
    static final String dropActionCacheTable = "drop table if exists actions_cache";
    static final String createActionCacheTable = "create table actions_cache " +
    		"select files.file_name, actions.file_id, actions.commit_id, actions.type " +
    		"from actions, files, file_types " +
    		"where actions.file_id=files.id and files.id=file_types.file_id and " +
    		"file_types.type='code' and files.repository_id=?";
    static final String findFileCount = "select count(distinct(file_name)) " +
    		"from files, file_types "
        + "where files.id = file_types.file_id and type = 'code' and repository_id=?";
    static final String findFileCountTime =  "select(" +
            "(select count(distinct(file_name)) from files, actions, scmlog, " +
                    "file_types where files.id=file_types.file_id and actions.commit_id = " +
                    "scmlog.id and actions.file_id = " +
                    " file_types.file_id and file_types.type = 'code' and scmlog.repository_id = " +
                    "? and scmlog.date < ?) - (" +
                    "select count(distinct(file_name)) from files, actions, scmlog, " +
                    "file_types where files.id=file_types.file_id and actions.commit_id = " +
                    "scmlog.id and actions.file_id = " +
                    " file_types.file_id and file_types.type = 'code' and scmlog.repository_id = " +
                    "? and scmlog.date < ? and actions.type = 'D')) as total_files";
    private PreparedStatement findCommitQuery;
    private PreparedStatement findFileQuery;
    private PreparedStatement findHunkIdQuery;
    private PreparedStatement findBugIntroCdateQuery;
    private static PreparedStatement findPidQuery;
    private static PreparedStatement dropCacheTableQuery;
    private static PreparedStatement createCacheTableQuery;
    private static PreparedStatement findFileCountQuery;
//    private PreparedStatement findFileCountTimeQuery;

    /**
     * From the actions table. See the cvsanaly manual
     * (http://gsyc.es/~carlosgc/files/cvsanaly.pdf), pg 11
     */
    public enum FileType {
        A, M, D, V, C, R
    }

    /**
     * Member fields
     */
    final int blocksize; // number of co-change files to import
    final int prefetchsize; // number of (new or modified but not buggy) files
    // to import
    final int cachesize; // size of cache
    final int pid; // project (repository) id
    final boolean saveToFile; // whether there should be csv output
    boolean filedistPrintMultiple = false; // whether the filedist output should happen once or more
    final CacheReplacement.Policy cacheRep; // cache replacement policy
    final Cache cache; // the cache
    final static Connection conn = DatabaseManager.getConnection(); // for database

    int hit;
    int miss;
    private int commits;
    private int totalcommits;
    private int bugcount;
    private int filesProcessed;

    // For output
    // XXX separate class to manage output
    String outputDate;
    int outputSpacing = 3; // output the hit rate every 3 months
    int month = outputSpacing;
    CsvWriter csvWriter;
    int fileCount; // XXX where is this set? why static?---This is no longer used now
    String filename;

    public Simulator(int bsize, int psize, int csize, int projid,
            CacheReplacement.Policy rep, String start, String end, Boolean save) {

        pid = projid;        
        int onepercent = getPercentOfFiles(pid);

        if (bsize == -1)
            blocksize = onepercent*5;
        else
            blocksize = bsize;
        if (csize == -1)
            cachesize = onepercent*10; 
        else
            cachesize = csize;
        if (psize == -1)
            prefetchsize = onepercent;
        else
            prefetchsize = psize;

        cacheRep = rep;

        start = findFirstDate(start, pid);
        end = findLastDate(end, pid);


        cache = new Cache(cachesize, new CacheReplacement(rep), start, end,
                projid);
        outputDate = cache.startDate;

        hit = 0;
        miss = 0;
        this.saveToFile = save;

        if (saveToFile == true) {
            filename = pid + "_" + cachesize + "_" + blocksize + "_"
            + prefetchsize + "_" + cacheRep;
            csvWriter = new CsvWriter("Results/" + filename + "_hitrate.csv");
            csvWriter.setComment('#');
            try {
                csvWriter.writeComment("hitrate for every 3 months, "
                        + "used to describe the variation of hit rate with time");
                csvWriter.writeComment("project: " + pid + ", cachesize: "
                        + cachesize + ", blocksize: " + cachesize
                        + ", prefetchsize: " + prefetchsize
                        + ", cache replacement policy: " + cacheRep);
                csvWriter.write("Month");
                //csvWriter.write("Range");
                csvWriter.write("HitRate");
                csvWriter.write("NumCommits");
                csvWriter.write("NumAdds");
                csvWriter.write("NumNewCacheItems");
                // csvWriter.write("NumFiles"); // uncomment if using findfilecountquery
                csvWriter.write("NumBugFixes");
                csvWriter.write("FilesProcessed");
                csvWriter.endRecord();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            findFileQuery = conn.prepareStatement(findFile);
            findCommitQuery = conn.prepareStatement(findCommit);
            findHunkIdQuery = conn.prepareStatement(findHunkId);
            findBugIntroCdateQuery = conn.prepareStatement(findBugIntroCdate);
            //findFileCountTimeQuery = conn.prepareStatement(findFileCountTime);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    void outputMultiple(){
        filedistPrintMultiple = true;
    }

    private static int getFileCount(int projid) {
        int ret = 0;
        try {
            findFileCountQuery = conn.prepareStatement(findFileCount);
            findFileCountQuery.setInt(1, projid);
            ret = Util.Database.getIntResult(findFileCountQuery);
        } catch (SQLException e1) {
            e1.printStackTrace();
        }
        return ret;
    }
    /**
     * Prints out the command line options
     */
    private static void printUsage() {
        System.err
        .println("Example Usage: FixCache -b=10000 -c=500 -f=600 -r=\"LRU\" -p=1");
        System.err.println("Example Usage: FixCache --blksize=10000 "
                + "--csize=500 --pfsize=600 --cacherep=\"LRU\" --pid=1");
        System.err.println("-p/--pid option is required");
    }

    /**
     * Loads an entity containing a bug into the cache.
     * 
     * @param fileId
     * @param cid
     *            -- commit id
     * @param commitDate
     *            -- commit date
     * @param intro_cdate
     *            -- bug introducing commit date
     */
    // XXX move hit and miss to the cache?
    // could add if (reas == BugEntity) logic to add() code
    public void loadBuggyEntity(String fileId, int cid, String commitDate, String intro_cdate) {

        bugcount++;
        if (cache.contains(fileId))
            hit++; 
        else
            miss++;

        cache.add(fileId, cid, commitDate, CacheItem.CacheReason.BugEntity);

        // add the co-changed files as well
        ArrayList<String> cochanges = CoChange.getCoChangeFileList(fileId,
                cache.startDate, intro_cdate, blocksize);
        cache.add(cochanges, cid, commitDate, CacheItem.CacheReason.CoChange);
    }

    /**
     * The main simulate loop. This loop processes all revisions starting at
     * cache.startDate
     * 
     */
    public void simulate() {

        final ResultSet allCommits;
        int cid;// means commit_id in actions
        String cdate = null;

        boolean isBugFix;
        String fileName;
        FileType type;
        int numprefetch = 0;

        // iterate over the selection
        try {
            findCommitQuery.setInt(1, pid);
            findCommitQuery.setString(2, cache.startDate);
            findCommitQuery.setString(3, cache.endDate);

            // returns all commits to pid after cache.startDate
            allCommits = findCommitQuery.executeQuery();
            while (allCommits.next()) {
                incCommits();
                cid = allCommits.getInt(1);
                cdate = allCommits.getString(2);
                isBugFix = allCommits.getBoolean(3);

                findFileQuery.setInt(1, cid);
                findFileQuery.setInt(2, cid);

                final ResultSet files = findFileQuery.executeQuery();
                // loop through those file ids
                while (files.next()) {
                    fileName = files.getString(1); //XXX fix query
                    type = FileType.valueOf(files.getString(2));
                    numprefetch = processOneFile(cid, cdate, isBugFix, fileName,
                            type, numprefetch);
                }
                numprefetch = 0;
                if (saveToFile == true) {
                    if (Util.Dates.getMonthDuration(outputDate, cdate) > outputSpacing
                            || cdate.equals(cache.endDate)) {
                        outputHitRate(cdate);
                    }
                }                   
            }      
        } catch (Exception e) {
            System.out.println(e);
            e.printStackTrace();
        }
    }

    private void incCommits() {
        commits++;
        totalcommits++;
    }

    private void outputHitRate(String cdate) {
        // XXX what if commits are more than 3 months apart?
        //final String formerOutputDate = outputDate;
        
        if (filedistPrintMultiple) 
            outputFileDist();

        if (!cdate.equals(cache.endDate)) {
            outputDate = Util.Dates.monthsLater(outputDate, outputSpacing);
        } else {
            outputDate = cdate; // = cache.endDate
        }

        try {
            csvWriter.write(Integer.toString(month));
            //csvWriter.write(Util.Dates.getRange(formerOutputDate, outputDate));
            csvWriter.write(Double.toString(getHitRate()));
            csvWriter.write(Integer.toString(resetCommitCount()));
            csvWriter.write(Integer.toString(cache.resetAddCount()));
            csvWriter.write(Integer.toString(cache.resetCICount()));
            //also prints filecount at time slice, but query is not accurate
            //csvWriter.write(Integer.toString(getFileCount(pid,cdate))); 
            csvWriter.write(Integer.toString(resetBugCount()));
            csvWriter.write(Integer.toString(resetFilesProcessedCount()));
            csvWriter.endRecord();
        } catch (IOException e) {
            e.printStackTrace();
        }
        month += outputSpacing;
        if (Util.Dates.getMonthDuration(outputDate, cdate) > outputSpacing){
            outputHitRate(cdate);
        }
        
    }

    private int processOneFile(int cid, String cdate, boolean isBugFix,
            String file_id, FileType type, int numprefetch) {
        filesProcessed++;
        
        switch (type) {
        case V:
            break;
        case R:
        case C:
        case A:
            if (numprefetch < prefetchsize) {
                numprefetch++;
                cache.add(file_id, cid, cdate, CacheItem.CacheReason.NewEntity);
            }
            break;
        case D:
            if(cache.contains(file_id)){
                this.cache.remove(file_id, cdate);
            }
            break;
        case M: // modified
            if (isBugFix) {
                String intro_cdate = this.getBugIntroCdate(file_id, cid);
                this.loadBuggyEntity(file_id, cid, cdate, intro_cdate);
            } else if (numprefetch < prefetchsize) {
                numprefetch++;
                cache.add(file_id, cid, cdate, CacheItem.CacheReason.ModifiedEntity);
            }
        }
        return numprefetch;
    }

    /**
     * Gets the current hit rate of the cache
     * 
     * @return hit rate of the cache
     */
    public double getHitRate() {
        double hitrate = (double) hit / (hit + miss);
        return (double) Math.round(hitrate * 10000) / 100;
    }

    /**
     * Database accessors
     */

    /**
     * Fills cache with pre-fetch size number of top-LOC files from initial
     * commit. Only called once per simulation // implicit input: initial commit
     * ID // implicit input: LOC for every file in initial commit ID // implicit
     * input: pre-fetch size
     */
    public void initialPreLoad() {
        System.out.println("initial preload");

        final String findInitialPreload = "select actions_cache.file_name, content_loc.commit_id"
            +" from actions_cache, scmlog, content_loc"
            +" where actions_cache.file_id=content_loc.file_id and actions_cache.commit_id=scmlog.id"
            +" and actions_cache.commit_id=content_loc.commit_id and actions_cache.type!='D' and"
            +" scmlog.date=? order by loc DESC";
//        final String findInitialPreload = "select files.file_name, content_loc.commit_id "
//            + "from content_loc, scmlog, actions, file_types, files "
//            + "where files.repository_id=? and content_loc.commit_id = scmlog.id and date =? "
//            + "and content_loc.file_id=actions.file_id and files.id=actions.file_id "
//            + "and content_loc.commit_id=actions.commit_id and actions.type!='D' "
//            + "and file_types.file_id=content_loc.file_id and file_types.type='code' " +
//            		"order by loc DESC";
        final PreparedStatement findInitialPreloadQuery;
        ResultSet r = null;
        String fileName = null;
        int commitId = 0;

        try {
            findInitialPreloadQuery = conn.prepareStatement(findInitialPreload);
            findInitialPreloadQuery.setString(1, cache.startDate);
            r = findInitialPreloadQuery.executeQuery();
        } catch (SQLException e1) {
            e1.printStackTrace();
        }

        for (int size = 0; size < prefetchsize; size++) {
            try {
                if (r.next()) {
                    fileName = r.getString(1); //XXX fix query
                    commitId = r.getInt(2);
                    cache.add(fileName, commitId, cache.startDate,
                            CacheItem.CacheReason.Preload);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Finds the first date after the startDate with repository entries. Only
     * called once per simulation.
     * 
     * @return The date for the prefetch.
     */
    private static String findFirstDate(String start, int pid) {
        String findFirstDate = "";
        final PreparedStatement findFirstDateQuery;
        String firstDate = "";
        try {
            if (start == null) {
                findFirstDate = "select min(date) from scmlog where repository_id=?";
                findFirstDateQuery = conn.prepareStatement(findFirstDate);
                findFirstDateQuery.setInt(1, pid);
            } else {
                findFirstDate = "select min(date) from scmlog where repository_id=? and date >=?";
                findFirstDateQuery = conn.prepareStatement(findFirstDate);
                findFirstDateQuery.setInt(1, pid);
                findFirstDateQuery.setString(2, start);
            }
            firstDate = Util.Database.getStringResult(findFirstDateQuery);
            if (firstDate == null) {
                System.out.println("Can not find any commit after "
                        + start);
                System.exit(2);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return firstDate;
    }

    /**
     * Finds the last date before the endDate with repository entries. Only
     * called once per simulation.
     * 
     * @return The date for the the simulator.
     */
    private static String findLastDate(String end, int pid) {
        String findLastDate = null;
        final PreparedStatement findLastDateQuery;
        String lastDate = null;
        try {
            if (end == null) {
                findLastDate = "select max(date) from scmlog where repository_id=?";
                findLastDateQuery = conn.prepareStatement(findLastDate);
                findLastDateQuery.setInt(1, pid);
            } else {
                findLastDate = "select max(date) from scmlog where repository_id=? and date <=?";
                findLastDateQuery = conn.prepareStatement(findLastDate);
                findLastDateQuery.setInt(1, pid);
                findLastDateQuery.setString(2, end);
            }
            lastDate = Util.Database.getStringResult(findLastDateQuery);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (lastDate == null) {
            System.out.println("Can not find any commit before "
                    + end);
            System.exit(2);
        }
        return lastDate;
    }

    /**
     * use the fileId and commitId to get a list of changed hunks from the hunk
     * table. for each changed hunk, get the blamedHunk from the hunk_blame
     * table; get the commit id associated with this blamed hunk take the
     * maximum (in terms of date?) commit id and return it
     * */

    public String getBugIntroCdate(String fileName, int commitId) {

        // XXX optimize this code?
        String bugIntroCdate = "";
        int hunkId;
        ResultSet r = null;
        ResultSet r1 = null;
        try {
            findHunkIdQuery.setString(1, fileName); // XXX fix query 
            findHunkIdQuery.setInt(2, commitId);
            r = findHunkIdQuery.executeQuery();
            while (r.next()) {
                hunkId = r.getInt(1);

                findBugIntroCdateQuery.setInt(1, hunkId);
                r1 = findBugIntroCdateQuery.executeQuery();
                while (r1.next()) {
                    if (r1.getString(1).compareTo(bugIntroCdate) > 0) {
                        bugIntroCdate = r1.getString(1);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println(e);
            System.exit(0);
        }

        return bugIntroCdate;
    }

    /**
     * Closes the database connection
     */
    private void close() {
        DatabaseManager.close();
    }

    /**
     * For Debugging
     */

    public int getHit() {
        return hit;
    }

    public int getMiss() {
        return miss;
    }

    public Cache getCache() {
        return cache;
    }

    public void add(String eid, int cid, String cdate, CacheReason reas) {
        cache.add(eid, cid, cdate, reas);
    }

    // XXX move to a different part of the file
    public static void checkParameter(String start, String end, int pid) {
        if (start != null && end != null) {
            if (start.compareTo(end) > 0) {
                System.err
                .println("Error:Start date must be earlier than end date");
                printUsage();
                System.exit(2);
            }
        }
        try {
            findPidQuery = conn.prepareStatement(findPid);
            findPidQuery.setInt(1, pid);
            if (Util.Database.getIntResult(findPidQuery) == -1) {
                System.out.println("There is no project whose id is " + pid);
                System.exit(2);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    public static void main(String args[]) {
    	long startTime = System.currentTimeMillis();
        /**
         * Command line parsing
         */
        CmdLineParser parser = new CmdLineParser();
        CmdLineParser.Option blksz_opt = parser.addIntegerOption('b', "blksize");
        CmdLineParser.Option csz_opt = parser.addIntegerOption('c', "csize");
        CmdLineParser.Option pfsz_opt = parser.addIntegerOption('f', "pfsize");
        CmdLineParser.Option crp_opt = parser.addStringOption('r', "cacherep");
        CmdLineParser.Option pid_opt = parser.addIntegerOption('p', "pid");
        CmdLineParser.Option sd_opt = parser.addStringOption('s', "start");
        CmdLineParser.Option ed_opt = parser.addStringOption('e', "end");
        CmdLineParser.Option save_opt = parser.addBooleanOption('o',"save");
        CmdLineParser.Option month_opt = parser.addBooleanOption('m',"multiple");
        CmdLineParser.Option tune_opt = parser.addBooleanOption('u', "tune");
        // CmdLineParser.Option sCId_opt = parser.addIntegerOption('s',"start");
        // CmdLineParser.Option eCId_opt = parser.addIntegerOption('e',"end");
        try {
            parser.parse(args);
        } catch (CmdLineParser.OptionException e) {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
        }

        Integer blksz = (Integer) parser.getOptionValue(blksz_opt, -1);
        Integer csz = (Integer) parser.getOptionValue(csz_opt, -1);
        Integer pfsz = (Integer) parser.getOptionValue(pfsz_opt, -1);
        String crp_string = (String) parser.getOptionValue(crp_opt,
                CacheReplacement.REPDEFAULT.toString());
        Integer pid = (Integer) parser.getOptionValue(pid_opt);
        String start = (String) parser.getOptionValue(sd_opt, null);
        String end = (String) parser.getOptionValue(ed_opt, null);
        Boolean saveToFile = (Boolean) parser.getOptionValue(save_opt, false);
        Boolean tune = (Boolean)parser.getOptionValue(tune_opt, false);
        Boolean monthly = (Boolean)parser.getOptionValue(month_opt, false);
        CacheReplacement.Policy crp;
        try {
            crp = CacheReplacement.Policy.valueOf(crp_string);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println("Must specify a valid cache replacement policy");
            printUsage();
            crp = CacheReplacement.REPDEFAULT;
        }
        if (pid == null) {
            System.err.println("Error: must specify a Project Id");
            printUsage();
            System.exit(2);
        }

        checkParameter(start, end, pid);
        createCacheTable(pid);
        /**
         * Create a new simulator and run simulation.
         */
        Simulator sim;

        if(tune)
        {
            System.out.println("tuning...");
            sim = tune(pid, blksz, pfsz, csz);
            System.out.println(".... finished tuning!");
            System.out.println("highest hitrate:"+sim.getHitRate());
        }
        else
        {
        	System.out.println("Time spent before creating Simulater: "+(System.currentTimeMillis()-startTime));
            sim = new Simulator(blksz, pfsz, csz, pid, crp, start, end, saveToFile);
        	System.out.println("After creating Simulater: "+(System.currentTimeMillis()-startTime));
            if (monthly) 
            	sim.outputMultiple();
            System.out.println("start initial preload: "+(System.currentTimeMillis()-startTime));
            sim.initialPreLoad();
            System.out.println("start simulate: "+(System.currentTimeMillis()-startTime));
            sim.simulate();
            System.out.println("end simulate"+(System.currentTimeMillis()-startTime));

            if(sim.saveToFile==true)
            {
                sim.csvWriter.close();
                sim.outputFileDist();
            }

        }

        // should always happen
        dropCacheTable();
        sim.close();
        printSummary(sim);
        System.out.println("end program"+(System.currentTimeMillis()-startTime));
    }


    public static void createCacheTable(int pid)
    {
        dropCacheTable();
        try{
             createCacheTableQuery = conn.prepareStatement(createActionCacheTable);
             createCacheTableQuery.setInt(1, pid);
             createCacheTableQuery.execute();
             Statement stmt = conn.createStatement();
             stmt.executeUpdate("create table content_loc_cache select * from content_loc");
             createIndex();
        }catch (SQLException e) {
            e.printStackTrace();}
       
    }
    
    public static void createIndex()
    {
        String createCommitIdIndex = "create index commitIdIndex on actions_cache(commit_id)";
        String createFileIdIndex = "create index fileIdIndex on actions_cache(file_id)";
        try{
            PreparedStatement createCommitIdIndexQuery=conn.prepareStatement(createCommitIdIndex);
            PreparedStatement createFileIdQuery = conn.prepareStatement(createFileIdIndex);
            createCommitIdIndexQuery.execute();
            createFileIdQuery.execute();
            Statement stmt = conn.createStatement();
            stmt.executeUpdate("create index contentLocCacheCommitIdIndex on content_loc_cache(commit_id)");
        }catch (SQLException e) {
            e.printStackTrace();}
        
    }
    public static void dropCacheTable()
    {
        try{
            dropCacheTableQuery = conn.prepareStatement(dropActionCacheTable);
            dropCacheTableQuery.execute();
            Statement stmt = conn.createStatement();
            stmt.executeUpdate("drop table if exists content_loc_cache");
       }catch (SQLException e) {
           e.printStackTrace();}
    }
    
    private static void printSummary(Simulator sim) {
        System.out.println("Simulator specs:");
        System.out.print("Project....");
        System.out.println(sim.pid);
        System.out.print("Cache size....");
        System.out.println(sim.cachesize);
        System.out.print("Blk size....");
        System.out.println(sim.blocksize);
        System.out.print("Prefetch size....");
        System.out.println(sim.prefetchsize);
        System.out.print("Start date....");
        System.out.println(sim.cache.startDate);
        System.out.print("End date....");
        System.out.println(sim.cache.endDate);
        System.out.print("Cache Replacement Policy ....");
        System.out.println(sim.cacheRep.toString());
        System.out.print("saving to file....");
        System.out.println(sim.saveToFile);


        System.out.println("\nResults:");

        System.out.print("Hit rate...");
        System.out.println(sim.getHitRate());

        System.out.print("Num commits processed...");
        System.out.println(sim.getTotalCommitCount());

        System.out.print("Num bug fixes...");
        System.out.println(sim.getHit() + sim.getMiss());
    }


    private static Simulator tune(int pid, int blksz, int pfsz, int csz)
    {
        Simulator maxsim = null;
        double maxhitrate = 0;
        
        boolean testblks = blksz < 0; 
        boolean testpfs = pfsz < 0; 
        
        if (testblks) blksz = 0;
        if (testpfs) pfsz = 0;
        
        if (csz < 0){
            System.out.println("Must specify a cache size to tune with");
            System.exit(1);
        }
        
        int onepercent = Math.round(csz/10);
        if (onepercent == 0) onepercent = 1; 
        int halfpercent = Math.round(onepercent/2);
        if (halfpercent == 0) halfpercent = 1; 
        int limit = Math.round(csz/2);

        System.out.print("Cache size: ");
        System.out.println(csz);
        System.out.println("One percent of files (assumed): " + onepercent);
        System.out.println("Half percent of files (assumed): " + halfpercent);
        System.out.println("Upper limit for blksize and pfsize: " + limit);
        
        CacheReplacement.Policy crp = CacheReplacement.REPDEFAULT;
        

        if (testblks){
            System.out.println("Testing blocksizes....");
            for(int b=onepercent;b<limit;b+=onepercent){
                final Simulator sim = new Simulator(b, pfsz, csz, pid, crp, null, null, false);
                sim.initialPreLoad();
                sim.simulate();
                System.out.print("blksize: ");
                System.out.println(b);
                System.out.print("hitrate: ");
                System.out.println(sim.getHitRate());
                if(sim.getHitRate()>maxhitrate)
                {
                    maxhitrate = sim.getHitRate();
                    maxsim = sim;
                    blksz = maxsim.blocksize;
                }else if (sim.getHitRate() < maxhitrate){
                    break; // hit rates decreasing
                }
            }
        }
        
        System.out.print("Best blksize: ");
        System.out.println(blksz);
        
        if (testpfs) {
            System.out.println("Testing prefetchsizes....");
            for(int p=halfpercent;p<limit;p+=halfpercent){
                final Simulator sim = new Simulator(blksz, p, csz, pid, crp, null, null, false);
                sim.initialPreLoad();
                sim.simulate();
                System.out.print("pfsz: ");
                System.out.println(p);
                System.out.print("hitrate: ");
                System.out.println(sim.getHitRate());
                if(sim.getHitRate()>maxhitrate)
                {
                    maxhitrate = sim.getHitRate();
                    maxsim = sim;
                    pfsz = maxsim.prefetchsize;
                }else if (sim.getHitRate() < maxhitrate){
                    break;
                }
            }
        }

        System.out.print("Best pfsize: ");
        System.out.println(pfsz);

        System.out.println("Testing cache replacements....");
        System.out.println("Trying out different cache replacment policies...");
        for(CacheReplacement.Policy crtst :CacheReplacement.Policy.values()){
            final Simulator sim = 
                new Simulator(blksz, pfsz,
                        csz, pid, crtst, null, null, false);
            sim.initialPreLoad();
            sim.simulate();
            System.out.print("Cache Replacement: ");
            System.out.println(crtst.toString());
            System.out.print("hitrate: ");
            System.out.println(sim.getHitRate());
            if(sim.getHitRate()>maxhitrate)
            {
                maxhitrate = sim.getHitRate();
                maxsim = sim;
            }
        }

        maxsim.close();
        return maxsim;
    }

    private static int getPercentOfFiles(int pid) {
        int ret =  (int) Math.round(getFileCount(pid)*0.01);
        if (ret == 0)
            return 1;
        else
            return ret;
    }

    private void outputFileDist() {
        String pathname;
        if (filedistPrintMultiple)
            pathname = "Results/" + month + "-" + filename + "_filedist.csv";
        else
            pathname = "Results/" + filename + "_filedist.csv";
        CsvWriter csv = new CsvWriter(pathname);
        csv.setComment('#');
        try {
            // csvWriter.write("# number of hit, misses and time stayed in Cache for every file");
            csv.writeComment("number of hit, misses and time stayed in Cache for every file");
            csv.writeComment("project: " + pid + ", cachesize: " + cachesize
                    + ", blocksize: " + cachesize + ", prefetchsize: "
                    + prefetchsize + ", cache replacement policy: " + cacheRep);
            csv.write("file_id");
            csv.write("loc");
            csv.write("num_load");
            csv.write("num_hits");
            csv.write("num_misses");
            csv.write("duration");
            csv.write("reason");
            csv.endRecord();
            csv.write("0");
            csv.write("0");
            csv.write("0");
            csv.write("0");
            csv.write("0");
            csv.write(Integer.toString(cache.getTotalDuration()));
            csv.write("0");
            csv.endRecord();
            // else assume that the file already has the correct header line
            // write out record
            //XXX rewrite with built in iteratable
            for (CacheItem ci : cache){
                csv.write((ci.getFileName()));
                csv.write(Integer.toString(ci.getLOC())); // LOC at time of last update
                csv.write(Integer.toString(ci.getLoadCount()));
                csv.write(Integer.toString(ci.getHitCount()));
                csv.write(Integer.toString(ci.getMissCount()));
                csv.write(Integer.toString(ci.getDuration()));
                csv.write(ci.getReason().toString());
                csv.endRecord();
            }

            csv.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int resetCommitCount() {
        int oldcommits = commits;
        commits = 0;
        return oldcommits;
    }

    private int resetBugCount() {
        int oldbugs = bugcount;
        bugcount = 0;
        return oldbugs;
    }

    private int resetFilesProcessedCount() {
        int oldfiles = filesProcessed;
        filesProcessed = 0;
        return oldfiles;
    }

    private int getTotalCommitCount() {
        return totalcommits;
    }

    public CsvWriter getCsvWriter() {
        return csvWriter;
    }
}
