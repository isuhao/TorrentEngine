/*
 * Created on Sep 9, 2008
 * Created by Paul Gardner
 *
 * Copyright (C) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */


package torrentlib.customization;

import java.io.*;
import java.util.*;

import controller.config.COConfigurationManager;
import torrentlib.BDecoder;
import torrentlib.ByteFormatter;
import torrentlib.Constants;
import torrentlib.Debug;
import torrentlib.util.FileUtil;
import torrentlib.SimpleTimer;
import torrentlib.SystemTime;
import torrentlib.TimerEvent;
import torrentlib.TimerEventPerformer;

import torrentlib.data.VuzeFile.VuzeFile;
import torrentlib.data.VuzeFile.VuzeFileComponent;
import torrentlib.data.VuzeFile.VuzeFileHandler;
import torrentlib.data.VuzeFile.VuzeFileProcessor;

public class
CustomizationManagerImpl
	implements CustomizationManager
{
	private static CustomizationManagerImpl		singleton = new CustomizationManagerImpl();

	public static CustomizationManager
	getSingleton()
	{
		return( singleton );
	}

	private boolean	initialised;

	private Map	customization_file_map = new HashMap();

	private String				current_customization_name;
	private CustomizationImpl	current_customization;

	protected
	CustomizationManagerImpl()
	{
	}

	public boolean
	preInitialize()
	{
	    File	user_dir = FileUtil.getUserFile("custom");

	    File	app_dir	 = FileUtil.getApplicationFile("custom");

	    boolean changed = preInitialize( app_dir );

	    if ( !user_dir.equals( app_dir )){

	    	if ( preInitialize( user_dir )){

	    		changed = true;
	    	}
	    }

		return( changed );
	}

	private boolean
	preInitialize(
		File	dir )
	{
		boolean changed = false;

		if ( dir.isDirectory()){

			File[]	files = dir.listFiles();

			if ( files != null ){

				for (int i=0;i<files.length;i++){

					File	file = files[i];

					String	name = file.getName();

					if ( !name.endsWith( ".config" )){

						continue;
					}

					FileInputStream	fis = null;

					boolean	ok = false;

					System.out.println( "Processing config presets: " + file );

					try{
						fis = new FileInputStream( file );

						Properties props = new Properties();

						props.load( fis );

						List<String> errors = new ArrayList<String>();

						for ( Map.Entry<Object,Object> entry: props.entrySet()){

							String	config_name 	= (String)entry.getKey();
							String	config_value 	= (String)entry.getValue();

							System.out.println( "\t" + config_name + " -> " + config_value );

							try{
								int	pos = config_value.indexOf( ':' );

								if ( pos == -1 ){

									throw( new Exception( "Value is invalid - missing type specification" ));
								}

								String	config_type = config_value.substring( 0, pos ).trim().toLowerCase();

								config_value = config_value.substring( pos+1 );

								if ( config_type.equals( "bool" )){

									config_value = config_value.trim().toLowerCase();

									boolean b;

									if ( config_value.equals( "true" )){

										b = true;

									}else if ( config_value.equals( "false" )){

										b = false;

									}else{

										throw( new Exception( "Invalid boolean value" ));
									}

									COConfigurationManager.setParameter( config_name, b );

								}else if ( config_type.equals( "long" )){

									long	l = Long.parseLong( config_value.trim());

									COConfigurationManager.setParameter( config_name, l );

								}else if ( config_type.equals( "float" )){

									float	f = Float.parseFloat( config_value.trim());

									COConfigurationManager.setParameter( config_name, f );

								}else if ( config_type.equals( "string" )){

									COConfigurationManager.setParameter( config_name, config_value );

								}else if ( config_type.equals( "byte[]" )){

									COConfigurationManager.setParameter( config_name, ByteFormatter.decodeString( config_value ));

								}else if ( config_type.equals( "list" )){

									COConfigurationManager.setParameter( config_name, (List)BDecoder.decode( ByteFormatter.decodeString( config_value )));

								}else if ( config_type.equals( "map" )){

									COConfigurationManager.setParameter( config_name, (Map)BDecoder.decode( ByteFormatter.decodeString( config_value )));

								}else{

									throw( new Exception( "Value is invalid - unknown type specifier" ));
								}

								changed = true;

							}catch( Throwable e ){

								errors.add( e.getMessage() + ": " + config_name + "=" + entry.getValue());
							}
						}

						if ( errors.size() > 0 ){

							throw( new Exception( "Found " + errors.size() + " errors: " + errors.toString()));
						}

						ok = true;

						System.out.println( "Presets applied" );

					}catch( Throwable e ){

						System.err.println( "Failed to process custom .config file " + file );

						e.printStackTrace();

					}finally{

						if ( fis != null ){

							try{
								fis.close();

							}catch( Throwable e ){

								e.printStackTrace();
							}
						}

						File	rename_target = new File( file.getAbsolutePath() + (ok?".applied":".bad" ));

						rename_target.delete();

						file.renameTo( rename_target );
					}
				}
			}
		}

		return( changed );
	}

	public void
	initialize()
	{
		synchronized( this ){

			if ( initialised ){

				return;
			}

			initialised = true;
		}

		VuzeFileHandler.getSingleton().addProcessor(
				new VuzeFileProcessor()
				{
					public void
					process(
						VuzeFile[]		files,
						int				expected_types )
					{
						for (int i=0;i<files.length;i++){

							VuzeFile	vf = files[i];

							VuzeFileComponent[] comps = vf.getComponents();

							for (int j=0;j<comps.length;j++){

								VuzeFileComponent comp = comps[j];

								if ( comp.getType() == VuzeFileComponent.COMP_TYPE_CUSTOMIZATION ){

									try{
										Map map = comp.getContent();

										((CustomizationManagerImpl)getSingleton()).importCustomization( map );

										comp.setProcessed();

									}catch( Throwable e ){

										Debug.printStackTrace(e);
									}
								}
							}
						}
					}
				});

	    File	user_dir = FileUtil.getUserFile("custom");

	    File	app_dir	 = FileUtil.getApplicationFile("custom");

	    loadCustomizations( app_dir );

	    if ( !user_dir.equals( app_dir )){

	    	loadCustomizations( user_dir );
	    }

	    String active = COConfigurationManager.getStringParameter( "customization.active.name", "" );

	    if ( customization_file_map.get( active ) == null ){

	    		// hmm, its been deleted or not set yet. look for new ones

	    	Iterator it = customization_file_map.keySet().iterator();

	    	while( it.hasNext()){

	    		String	name = (String)it.next();

	    		final String version_key = "customization.name." + name + ".version";

	    		String existing_version = COConfigurationManager.getStringParameter( version_key, "0" );

	    		if ( existing_version.equals( "0" )){

	    			active = name;

	    			String version = ((String[])customization_file_map.get( name ))[0];

	    			COConfigurationManager.setParameter( "customization.active.name", active );

	    			COConfigurationManager.setParameter( version_key, version );

	    			break;
	    		}
	    	}
	    }

	    synchronized( this ){

	    	current_customization_name = active;
	    }
	}

	protected void
	loadCustomizations(
		File		dir )
	{
		if ( dir.isDirectory()){

			File[]	files = dir.listFiles();

			if ( files != null ){

				for (int i=0;i<files.length;i++){

					File	file = files[i];

					String	name = file.getName();

					if ( !name.endsWith( ".zip" )){

						if ( !name.contains( ".config" )){

							logInvalid( file );
						}

						continue;
					}

					String	base = name.substring( 0, name.length() - 4 );

					int	u_pos = base.lastIndexOf( '_' );

					if ( u_pos == -1 ){

						logInvalid( file );

						continue;
					}

					String	lhs = base.substring(0,u_pos).trim();
					String	rhs	= base.substring(u_pos+1).trim();

					if ( lhs.length() == 0 || !Constants.isValidVersionFormat( rhs )){

						logInvalid( file );

						continue;
					}

					String[]	details = (String[])customization_file_map.get( lhs );

					if ( details == null ){

						customization_file_map.put( lhs, new String[]{ rhs, file.getAbsolutePath()});

					}else{

						String	old_version = details[0];

						if ( Constants.compareVersions( old_version, rhs ) < 0 ){

							customization_file_map.put( lhs, new String[]{ rhs, file.getAbsolutePath()});
						}
					}
				}
			}
		}
	}

	protected void
	logInvalid(
		File file )
	{
		Debug.out( "Invalid customization file name '" + file.getAbsolutePath() + "' - format must be <name>_<version>.zip where version is numeric and dot separated" );
	}

	protected void
	importCustomization(
		Map		map )

		throws CustomizationException
	{
		try{
			String	name 	= new String((byte[])map.get( "name" ), "UTF-8" );

			String	version = new String((byte[])map.get( "version" ), "UTF-8" );

			if ( !Constants.isValidVersionFormat( version )){

				throw( new CustomizationException( "Invalid version specification: " + version ));
			}

			byte[]	data = (byte[])map.get( "data" );

		    File	user_dir = FileUtil.getUserFile("custom");

		    if ( !user_dir.exists()){

		    	user_dir.mkdirs();
		    }

		    File	target = new File( user_dir, name + "_" + version + ".zip" );

		    if ( !target.exists()){

		    	if ( !FileUtil.writeBytesAsFile2( target.getAbsolutePath(), data )){

		    		throw( new CustomizationException( "Failed to save customization to " + target ));
		    	}
		    }
		}catch( CustomizationException e ){

			throw( e );

		}catch( Throwable e ){

			throw( new CustomizationException( "Failed to import customization", e ));
		}
	}

	protected void
	exportCustomization(
		CustomizationImpl	cust,
		File				to_file )

		throws CustomizationException
	{
		if ( to_file.isDirectory()){

			to_file = new File( to_file, cust.getName() + "_" + cust.getVersion() + ".vuze");
		}

		if ( !to_file.getName().endsWith( ".vuze" )){

			to_file = new File( to_file.getParentFile(), to_file.getName() + ".vuze" );
		}

		try{
			Map	contents = new HashMap();

			byte[]	data = FileUtil.readFileAsByteArray( cust.getContents());

			contents.put( "name", cust.getName());
			contents.put( "version", cust.getVersion());
			contents.put( "data", data );

			VuzeFile	vf = VuzeFileHandler.getSingleton().create();

			vf.addComponent(
				VuzeFileComponent.COMP_TYPE_CUSTOMIZATION,
				contents);

			vf.write( to_file );

		}catch( Throwable e ){

			throw( new CustomizationException( "Failed to export customization", e ));
		}
	}

	public Customization
	getActiveCustomization()
	{
		synchronized( this ){

			if ( current_customization == null ){

				if ( current_customization_name != null ){

					String[] entry = (String[])customization_file_map.get( current_customization_name );

					if ( entry != null ){

						try{
							current_customization =
								new CustomizationImpl(
									this,
									current_customization_name,
									entry[0],
									new File( entry[1] ));

							SimpleTimer.addEvent(
								"Custom:clear",
								SystemTime.getCurrentTime() + 120*1000,
								new TimerEventPerformer()
								{
									public void
									perform(
										TimerEvent event )
									{
										synchronized( CustomizationManagerImpl.this ){

											current_customization = null;
										}
									}
								});

						}catch( CustomizationException e ){

							e.printStackTrace();
						}
					}
				}
			}

			return( current_customization );
		}
	}

	public Customization[]
	getCustomizations()
	{
		List	result = new ArrayList();

		synchronized( this ){

			Iterator	it = customization_file_map.entrySet().iterator();

			while( it.hasNext()){

				Map.Entry	entry = (Map.Entry)it.next();

				String		name = (String)entry.getKey();
				String[]	bits = (String[])entry.getValue();

				String	version = (String)bits[0];
				File	file	= new File(bits[1]);

				try{

					CustomizationImpl cust = new CustomizationImpl( this, name, version, file );

					result.add( cust );

				}catch( Throwable e ){
				}
			}
		}

		return((Customization[])result.toArray(new Customization[result.size()]));
	}

	public static void
	main(
		String[]		args )
	{
		/*
		try{
			CustomizationManagerImpl	manager = (CustomizationManagerImpl)getSingleton();

			CustomizationImpl cust = new CustomizationImpl( manager, "blah", "1.2", new File( "C:\\temp\\cust\\details.zip" ));

			cust.exportToVuzeFile( new File( "C:\\temp\\cust" ));

		}catch( Throwable e ){

			e.printStackTrace();
		}
		*/

		try{
			VuzeFile	vf = VuzeFileHandler.getSingleton().create();

			Map	config = new HashMap();

			List list = new ArrayList();

			list.add( "trout" );
			list.add( 45 );

			config.put( "test.a10", "Hello mum" );
			config.put( "test.a11", new Long(100));
			config.put( "test.a13", list );

			Map	map = new HashMap();

			map.put( "name", "My Proxy Settings" );
			map.put( "settings", config );
			map.put( "restart", new Long( 1 ));

			vf.addComponent( VuzeFileComponent.COMP_TYPE_CONFIG_SETTINGS, map );

			vf.write( new File( "C:\\temp\\p_config.vuze" ) );

		}catch( Throwable e ){

			e.printStackTrace();
		}
	}
}
