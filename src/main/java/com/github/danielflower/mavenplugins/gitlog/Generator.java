package com.github.danielflower.mavenplugins.gitlog;

import com.github.danielflower.mavenplugins.gitlog.filters.CommitFilter;
import com.github.danielflower.mavenplugins.gitlog.renderers.ChangeLogRenderer;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.File;
import java.io.IOException;
import java.util.*;

class Generator {

	private final List<ChangeLogRenderer> renderers;
	private RevWalk walk;
	private ArrayList<Pair<RevCommit, ArrayList<RevTag>>> allTaggedCommits;
	private final List<CommitFilter> commitFilters;
	private final Log log;
	private RevCommit mostRecentCommit;

	public Generator(List<ChangeLogRenderer> renderers, List<CommitFilter> commitFilters, Log log) {
		this.renderers = renderers;
		this.commitFilters = (commitFilters == null) ? new ArrayList<CommitFilter>() : commitFilters;
		this.log = log;
	}

	public Repository openRepository() throws IOException, NoGitRepositoryException {
		return openRepository(null);
	}

	public Repository openRepository(File gitdir) throws IOException, NoGitRepositoryException {
		log.debug("About to open git repository.");
		Repository repository;
		try {
                    if ( gitdir == null ) {
			repository = new RepositoryBuilder().findGitDir().build();
                    } else {
			repository = new RepositoryBuilder().findGitDir(gitdir).build();
                    }
		} catch (IllegalArgumentException iae) {
			throw new NoGitRepositoryException();
		}
		log.debug("Opened " + repository + ". About to load the commits.");
		walk = createWalk(repository);
		log.debug("Loaded commits. about to load the tags.");
		allTaggedCommits = taggedCommits(repository, walk);
		log.debug("Loaded tagged commits: " + allTaggedCommits);

		return repository;
	}

	public void generate(String reportTitle) throws IOException {
		generate(reportTitle, new Date(0L));
	}

	public void generate(String reportTitle, Date includeCommitsAfter) throws IOException {
		for (ChangeLogRenderer renderer : renderers) {
			renderer.renderHeader(reportTitle);
		}

		long includeCommitsAfterSecondsSinceEpoch = includeCommitsAfter.getTime() / 1000;

		List<RevCommit> commits = getAllCommits(includeCommitsAfterSecondsSinceEpoch);

		ArrayList<Pair<RevCommit, ArrayList<RevTag>>> taggedCommits = removeTaggedCommitsOlderThan(allTaggedCommits, includeCommitsAfterSecondsSinceEpoch);
		sortTaggedCommitsOldestFirst(taggedCommits);

		Map<String, List<RevCommit>> mergedCommitsMap = getMergedCommitsMap(commits, taggedCommits);
		walk.dispose();

		// Reverse the list of tagged commits to get the latest first
		Collections.reverse(taggedCommits);

		render(taggedCommits, mergedCommitsMap);

		for (ChangeLogRenderer renderer : renderers) {
			renderer.renderFooter();
			renderer.close();
		}
	}

	private List<RevCommit> getAllCommits(long includeCommitsAfterSecondsSinceEpoch) {
		List<RevCommit> allCommits = new ArrayList<RevCommit>();
		for (RevCommit revCommit : walk) {
			if (revCommit.getCommitTime() >= includeCommitsAfterSecondsSinceEpoch) {
				allCommits.add(revCommit);
			}
		}
		return allCommits;
	}

	private Map<String, List<RevCommit>> getMergedCommitsMap(List<RevCommit> commits, List<Pair<RevCommit, ArrayList<RevTag>>> taggedCommits) throws IOException {
		Map<String, List<RevCommit>> mergedCommitsMap = new HashMap<String, List<RevCommit>>();
		for (Pair<RevCommit, ArrayList<RevTag>> taggedCommit : taggedCommits) {
			ArrayList<RevCommit> mergedCommits = new ArrayList<RevCommit>();
			for (RevCommit commit : commits) {
				if (commit.getCommitTime() <= taggedCommit.getLeft().getCommitTime() && walk.isMergedInto(commit, taggedCommit.getLeft())) {
					mergedCommits.add(commit);
				}
			}

			commits.removeAll(mergedCommits);
			mergedCommitsMap.put(taggedCommit.getLeft().name(), mergedCommits);
		}

		return mergedCommitsMap;
	}

	private void render(List<Pair<RevCommit, ArrayList<RevTag>>> taggedCommits, Map<String, List<RevCommit>> mergedCommitsMap) throws IOException {
		for (Pair<RevCommit, ArrayList<RevTag>> taggedCommit : taggedCommits) {
			for (ChangeLogRenderer renderer : renderers) {
				for (RevTag revTag : taggedCommit.getRight()) {
					renderer.renderTag(revTag);
				}
			}

			List<RevCommit> revCommits = mergedCommitsMap.get(taggedCommit.getLeft().name());
			for (RevCommit revCommit : revCommits) {
				if (show(revCommit)) {
					for (ChangeLogRenderer renderer : renderers) {
						renderer.renderCommit(revCommit);
					}
				}
			}
		}
	}

	private ArrayList<Pair<RevCommit, ArrayList<RevTag>>> removeTaggedCommitsOlderThan(ArrayList<Pair<RevCommit, ArrayList<RevTag>>> taggedCommits, long includeCommitsAfterSecondsSinceEpoch) {
		ArrayList<Pair<RevCommit, ArrayList<RevTag>>> commitsToKeep = new ArrayList<Pair<RevCommit, ArrayList<RevTag>>>();
		for (Pair<RevCommit, ArrayList<RevTag>> commit : taggedCommits) {
			if (commit.getLeft().getCommitTime() >= includeCommitsAfterSecondsSinceEpoch) {
				commitsToKeep.add(commit);
			}
		}
		return commitsToKeep;
	}

	private boolean show(RevCommit commit) {
		for (CommitFilter commitFilter : commitFilters) {
			if (!commitFilter.renderCommit(commit)) {
				log.debug("Commit filtered out by " + commitFilter.getClass().getSimpleName());
				return false;
			}
		}
		return true;
	}

	private RevWalk createWalk(Repository repository) throws IOException {
		RevWalk walk = new RevWalk(repository);
		ObjectId head = repository.resolve("HEAD");
		if (head != null) {
			// if head is null, it means there are no commits in the repository.  The walk will be empty.
			mostRecentCommit = walk.parseCommit(head);
			walk.markStart(mostRecentCommit);
		}
		return walk;
	}


	private ArrayList<Pair<RevCommit, ArrayList<RevTag>>> taggedCommits(Repository repository, RevWalk revWalk) throws IOException {
		Map<String, Ref> allTags = repository.getTags();

		Map<String, Pair<RevCommit, ArrayList<RevTag>>> revTags = new HashMap<String, Pair<RevCommit, ArrayList<RevTag>>>();

		// Add head. If it also has tags they will be added while looping tags
		revTags.put(mostRecentCommit.name(), Pair.of(mostRecentCommit, new ArrayList<RevTag>()));

		for (Ref ref : allTags.values()) {
			try {
				RevTag revTag = revWalk.parseTag(ref.getObjectId());
				RevCommit revCommit = revWalk.parseCommit(ref.getObjectId());
				String commitID = revCommit.name();
				if (!revTags.containsKey(commitID)) {
					revTags.put(commitID, Pair.of(revCommit, new ArrayList<RevTag>()));
				}
				revTags.get(commitID).getRight().add(revTag);
			} catch (IncorrectObjectTypeException e) {
				log.debug("Light-weight tags not supported. Skipping " + ref.getName());
			}
		}

		return new ArrayList<Pair<RevCommit, ArrayList<RevTag>>>(revTags.values());
	}

	private void sortTaggedCommitsOldestFirst(ArrayList<Pair<RevCommit, ArrayList<RevTag>>> taggedCommits) {
		Collections.sort(taggedCommits, new Comparator<Pair<RevCommit, ArrayList<RevTag>>>() {
			@Override
			public int compare(Pair<RevCommit, ArrayList<RevTag>> o1, Pair<RevCommit, ArrayList<RevTag>> o2) {
				return Integer.valueOf(o1.getLeft().getCommitTime()).compareTo(o2.getLeft().getCommitTime());
			}
		});
	}
}
