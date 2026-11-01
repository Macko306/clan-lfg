package com.clanlfg;

import java.util.Collections;
import java.util.List;

/** One visible group member, as reported by the server. Immutable. */
public final class Member
{
	private final String id;
	private final String rsn;
	private final List<Tag> tags;
	private final int world;
	private final long expiresAt; // server clock, epoch ms; 0 = until they log out
	private final String status; // short message, already cleaned; "" = none

	public Member(String id, String rsn, List<Tag> tags, int world, long expiresAt)
	{
		this(id, rsn, tags, world, expiresAt, "");
	}

	public Member(String id, String rsn, List<Tag> tags, int world, long expiresAt, String status)
	{
		this.id = id;
		this.rsn = rsn;
		this.tags = Collections.unmodifiableList(tags);
		this.world = world;
		this.expiresAt = expiresAt;
		this.status = Status.clean(status);
	}

	public String getId()
	{
		return id;
	}

	public String getRsn()
	{
		return rsn;
	}

	public List<Tag> getTags()
	{
		return tags;
	}

	public int getWorld()
	{
		return world;
	}

	public long getExpiresAt()
	{
		return expiresAt;
	}

	public String getStatus()
	{
		return status;
	}
}
