/**
* Global Sensor Networks (GSN) Source Code
* Copyright (c) 2006-2016, Ecole Polytechnique Federale de Lausanne (EPFL)
* 
* This file is part of GSN.
* 
* GSN is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* 
* GSN is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* 
* You should have received a copy of the GNU General Public License
* along with GSN.  If not, see <http://www.gnu.org/licenses/>.
* 
* File: src/gsn/networking/zeromq/ZeroMQWrapperPush.java
*
* @author Julien Eberle
*
*/

package gsn.networking.zeromq;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.zeromq.ZContext;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.zeromq.ZMQ;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;

import gsn.Main;
import gsn.beans.AddressBean;
import gsn.beans.DataField;
import gsn.beans.StreamElement;
import gsn.delivery.StreamElement4Rest;
import gsn.wrappers.AbstractWrapper;

public class ZeroMQWrapperPush extends AbstractWrapper {
	
	private transient Logger logger = LoggerFactory.getLogger( this.getClass() );
	private DataField[] structure;
	private Kryo kryo = new Kryo();
	private ZMQ.Socket receiver = null;
	private int lport = 0;
	private String laddress;


	@Override
	public boolean initialize() {
		
		kryo.register(StreamElement4Rest.class);
		kryo.register(DataField[].class);

		AddressBean addressBean = getActiveAddressBean();
		
		structure = getActiveAddressBean().getOutputStructure();

		String _lport = addressBean.getPredicateValue("local_port");
		laddress = addressBean.getPredicateValue("local_adress");

		if ( laddress == null || laddress.trim().length() == 0 ) 
			throw new RuntimeException( "The >local_address< parameter is missing from the ZeroMQ wrapper." );
		if (_lport != null){
			lport = Integer.parseInt(_lport); 
			if ( lport <= 0 || lport > 65535 ) 
				throw new RuntimeException( "The >local_port< parameter must be a valid port number." );
		} else {
			throw new RuntimeException( "The >local_port< parameter is missing from the ZeroMQ wrapper." );
		}
		
		ZContext ctx = Main.getZmqContext();
		receiver = ctx.createSocket(ZMQ.REP);
		receiver.bind("tcp://*:"+lport);
		receiver.setReceiveTimeOut(10000);

        return true;
	}

	@Override
	public void dispose() {
		
	}

	@Override
	public String getWrapperName() {
		return "ZeroMQ wrapper Push";
	}
	
	@Override
	public void run(){
		
		while (isActive()) {
			try{
				byte[] rec = receiver.recv();
				if (rec != null){
					ByteArrayInputStream bais = new ByteArrayInputStream(rec);
					StreamElement se = kryo.readObjectOrNull(new Input(bais),StreamElement.class);
			        boolean success = postStreamElement(se);
			        receiver.send(success ? new byte[]{(byte)0} : new byte[]{(byte)1});
				}
			}catch (Exception e)
			{
				logger.error("ZMQ wrapper error: ",e);
			}
		}
		receiver.close();
	}
	
	/**
     * if the structure is not defined in the xml file, throws an exception.
     */
    @Override
    public DataField[] getOutputFormat() throws RuntimeException{ 
    	if (structure == null){throw new RuntimeException("ZMQ Push wrapper has an undefined output structure.");}
    	return structure;
    }
	
	   @Override
	   public boolean isTimeStampUnique(){
		   return false;
	   }

}
