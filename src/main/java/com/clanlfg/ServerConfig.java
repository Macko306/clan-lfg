package com.clanlfg;

import java.net.URI;

/**
 * The one place that says which server the plugin talks to.
 *
 * The address is deliberately not a user setting, so nobody can be pointed at another server through a
 * config file. Developers can override it for local testing with -Dclanlfg.server=http://localhost:8787
 * (see the run task in build.gradle); plain http is only accepted for localhost.
 */
final class ServerConfig
{
	static final String PRODUCTION_URL = "https://clan-lfg.clan-lfg-worker.workers.dev";

	private ServerConfig()
	{
	}

	static String baseUrl()
	{
		String override = System.getProperty("clanlfg.server");
		String url = override != null && !override.trim().isEmpty() ? override.trim() : PRODUCTION_URL;
		while (url.endsWith("/"))
		{
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	static boolean isUsable(String url)
	{
		try
		{
			URI uri = URI.create(url);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (host == null)
			{
				return false;
			}
			if ("https".equals(scheme))
			{
				return true;
			}
			return "http".equals(scheme) && (host.equals("localhost") || host.equals("127.0.0.1"));
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}
}
